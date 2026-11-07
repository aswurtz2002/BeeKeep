import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";
import {
  base64ToBytes,
  sha256Hex,
  verifyEcdsaP256Signature,
} from "./crypto.ts";

type JsonRecord = Record<string, unknown>;

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function respond(status: number, body: JsonRecord): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...corsHeaders,
      "Content-Type": "application/json",
      "Cache-Control": "no-store",
      "Pragma": "no-cache",
    },
  });
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function readKeySet(name: string, legacyName: string): string {
  const value = Deno.env.get(name);
  if (value) {
    try {
      const parsed = JSON.parse(value) as Record<string, string>;
      if (typeof parsed.default === "string" && parsed.default.length > 0) return parsed.default;
    } catch {
      // Legacy environment variable remains a supported fallback.
    }
  }
  return Deno.env.get(legacyName) ?? "";
}

function decodeJwtPayload(token: string): JsonRecord {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("Invalid access token.");
  const normalized = parts[1].replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const bytes = base64ToBytes(padded);
  const decoded = new TextDecoder().decode(bytes);
  const value: unknown = JSON.parse(decoded);
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Invalid access token claims.");
  }
  return value as JsonRecord;
}

function recentEmailOtp(claims: JsonRecord): boolean {
  const amr = claims.amr;
  if (!Array.isArray(amr)) return false;
  const now = Math.floor(Date.now() / 1000);
  return amr.some((entry) => {
    if (!entry || typeof entry !== "object") return false;
    const method = (entry as JsonRecord).method;
    const timestamp = (entry as JsonRecord).timestamp;
    return method === "otp" && typeof timestamp === "number" &&
      Number.isFinite(timestamp) && timestamp <= now + 60 && now - timestamp <= 10 * 60;
  });
}

function requiredString(value: unknown, name: string, maxLength = 4096): string {
  if (typeof value !== "string" || value.trim().length === 0 || value.length > maxLength) {
    throw new Error(`Invalid ${name}.`);
  }
  return value.trim();
}

type RpcResult = { data: unknown; error: { code?: string } | null };
type RpcClient = {
  rpc: (functionName: string, args: Record<string, unknown>) => PromiseLike<RpcResult>;
};

async function rpcData<T>(
  client: unknown,
  functionName: string,
  args: Record<string, unknown>,
): Promise<T> {
  // The SDK client schema is intentionally supplied at runtime by PostgREST here.
  // Narrow the external client to only the RPC surface this endpoint needs.
  const rpcClient = client as RpcClient;
  const { data, error } = await rpcClient.rpc(functionName, args);
  if (error) {
    console.error("Device access RPC failed:", functionName, error.code ?? "unknown");
    throw new Error("The phone approval request could not be completed.");
  }
  return data as T;
}

function sameHex(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let i = 0; i < left.length; i++) {
    difference |= left.charCodeAt(i) ^ right.charCodeAt(i);
  }
  return difference === 0;
}

async function requireValidSignature(
  publicKey: string,
  signature: string,
  message: string,
): Promise<void> {
  let valid = false;
  try {
    valid = await verifyEcdsaP256Signature(publicKey, signature, message);
  } catch {
    valid = false;
  }
  if (!valid) throw new Error("The phone could not prove ownership of its security key.");
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return respond(405, { success: false, error: "Method not allowed." });

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const publicKey = readKeySet("SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_ANON_KEY") ||
      Deno.env.get("SUPABASE_PUBLISHABLE_KEY") || "";
    // The project currently exposes its legacy service_role JWT to Edge Functions.
    // A new sb_secret_ key is not a JWT and must not be sent as Authorization: Bearer.
    // Migrate this client to @supabase/server before switching to a secret API key.
    const secretKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
    if (!supabaseUrl || !publicKey || !secretKey) {
      return respond(503, { success: false, error: "BeeKeep phone approval is not configured on this server." });
    }

    const authorization = req.headers.get("Authorization") ?? "";
    const bearer = authorization.match(/^Bearer\s+(.+)$/i)?.[1];
    if (!bearer || bearer.length > 16384) {
      return respond(401, { success: false, error: "Sign in to BeeKeep first." });
    }

    const userClient = createClient(supabaseUrl, publicKey, {
      global: { headers: { Authorization: `Bearer ${bearer}` } },
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });
    const { data: userData, error: authError } = await userClient.auth.getUser(bearer);
    if (authError || !userData.user) {
      return respond(401, { success: false, error: "Your BeeKeep sign-in session is no longer valid." });
    }

    const claims = decodeJwtPayload(bearer);
    const userId = userData.user.id;
    const sessionId = claims.session_id;
    const expiration = claims.exp;
    if (claims.sub !== userId || !isUuid(sessionId) ||
        typeof expiration !== "number" || expiration <= Math.floor(Date.now() / 1000)) {
      return respond(401, { success: false, error: "Your BeeKeep sign-in session could not be verified." });
    }

    const adminClient = createClient(supabaseUrl, secretKey, {
      auth: { persistSession: false, autoRefreshToken: false, detectSessionInUrl: false },
    });

    let body: JsonRecord;
    try {
      const rawBody = await req.text();
      if (new TextEncoder().encode(rawBody).length > 16384) {
        return respond(413, { success: false, error: "The phone approval request is too large." });
      }
      const parsed: unknown = JSON.parse(rawBody);
      if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
        return respond(400, { success: false, error: "Expected a JSON request body." });
      }
      body = parsed as JsonRecord;
    } catch {
      return respond(400, { success: false, error: "Expected a valid JSON request body." });
    }

    const action = requiredString(body.action, "action", 32).toLowerCase();
    const requestId = body.request_id;
    const signedChallenge = body.challenge;
    const signature = body.signature;

    switch (action) {
      case "access": {
        const trusted = await rpcData<boolean>(
          userClient,
          "beekeep_device_session_is_trusted",
          {},
        );
        return respond(200, { success: true, trusted: trusted === true });
      }

      case "begin": {
        const publicDeviceKey = requiredString(body.public_key, "public key", 4096);
        const label = requiredString(body.device_label, "device label", 80);
        const data = await rpcData<unknown>(userClient, "beekeep_device_begin_request", {
          p_public_key: publicDeviceKey,
          p_device_label: label,
        });
        return respond(200, { success: true, request: data });
      }

      case "status": {
        const data = await rpcData<unknown>(userClient, "beekeep_device_get_request_status", {});
        return respond(200, { success: true, request: data });
      }

      case "list": {
        const data = await rpcData<unknown>(userClient, "beekeep_device_list_pending_requests", {});
        return respond(200, { success: true, requests: data });
      }

      case "approval_challenge": {
        if (!isUuid(requestId)) return respond(400, { success: false, error: "Invalid phone request." });
        const data = await rpcData<string>(userClient, "beekeep_device_issue_approval_challenge", {
          p_request_id: requestId,
        });
        return respond(200, { success: true, challenge: data });
      }

      case "complete": {
        if (!isUuid(requestId)) return respond(400, { success: false, error: "Invalid phone request." });
        const challenge = requiredString(signedChallenge, "challenge", 256);
        const signed = requiredString(signature, "signature", 2048);
        const rows = await rpcData<Array<JsonRecord>>(
          adminClient,
          "beekeep_internal_device_request_verification_context",
          {
            p_request_id: requestId,
            p_user_id: userId,
            p_session_id: sessionId,
          },
        );
        const context = Array.isArray(rows) ? rows[0] : null;
        if (!context || context.request_status !== "pending" ||
            typeof context.expires_at !== "string" ||
            Date.parse(context.expires_at) <= Date.now() ||
            typeof context.challenge_hash !== "string" ||
            typeof context.public_key !== "string" ||
            typeof context.request_mode !== "string") {
          return respond(410, { success: false, error: "This phone request has expired. Start again." });
        }
        if (!sameHex(await sha256Hex(challenge), context.challenge_hash)) {
          return respond(403, { success: false, error: "The phone registration challenge is not valid." });
        }
        await requireValidSignature(context.public_key, signed, challenge);
        const marked = await rpcData<boolean>(adminClient, "beekeep_internal_device_mark_request_verified", {
          p_request_id: requestId,
          p_user_id: userId,
          p_session_id: sessionId,
          p_challenge_hash: context.challenge_hash,
        });
        if (!marked) return respond(410, { success: false, error: "This phone request has expired. Start again." });

        if (context.request_mode === "same_device_rebind") {
          const deviceId = await rpcData<string>(adminClient, "beekeep_internal_device_rebind_session", {
            p_request_id: requestId,
            p_user_id: userId,
            p_session_id: sessionId,
            p_challenge_hash: context.challenge_hash,
          });
          return respond(200, { success: true, status: "approved", device_id: deviceId });
        }
        return respond(200, { success: true, status: "pending_approval" });
      }

      case "approve":
      case "deny": {
        if (!isUuid(requestId)) return respond(400, { success: false, error: "Invalid phone request." });
        const signed = requiredString(signature, "signature", 2048);
        const rows = await rpcData<Array<JsonRecord>>(
          adminClient,
          "beekeep_internal_device_approval_context",
          {
            p_request_id: requestId,
            p_user_id: userId,
            p_session_id: sessionId,
          },
        );
        const context = Array.isArray(rows) ? rows[0] : null;
        if (!context || typeof context.trusted_public_key !== "string" ||
            typeof context.approval_challenge_hash !== "string" ||
            typeof context.approval_challenge_expires_at !== "string" ||
            Date.parse(context.approval_challenge_expires_at) <= Date.now()) {
          return respond(410, { success: false, error: "The approval request expired. Open it again on the trusted phone." });
        }
        const approvalChallenge = requiredString(signedChallenge, "approval challenge", 256);
        if (!sameHex(await sha256Hex(approvalChallenge), context.approval_challenge_hash)) {
          return respond(403, { success: false, error: "The approval challenge is not valid." });
        }
        await requireValidSignature(context.trusted_public_key, signed, approvalChallenge);
        const rpcName = action === "approve"
          ? "beekeep_internal_device_approve_request"
          : "beekeep_internal_device_deny_request";
        const result = await rpcData<unknown>(adminClient, rpcName, {
          p_request_id: requestId,
          p_user_id: userId,
          p_approver_session_id: sessionId,
          p_approval_challenge_hash: context.approval_challenge_hash,
        });
        return respond(200, {
          success: true,
          status: action === "approve" ? "approved" : (result ? "denied" : "expired"),
        });
      }

      case "recover": {
        if (!isUuid(requestId)) return respond(400, { success: false, error: "Invalid phone request." });
        if (!recentEmailOtp(claims)) {
          return respond(403, { success: false, error: "Verify the six-digit email security code before recovering this phone." });
        }
        if (!userData.user.email_confirmed_at) {
          return respond(403, { success: false, error: "Confirm your BeeKeep account email before recovering a phone." });
        }
        const challenge = requiredString(signedChallenge, "challenge", 256);
        const signed = requiredString(signature, "signature", 2048);
        const rows = await rpcData<Array<JsonRecord>>(
          adminClient,
          "beekeep_internal_device_request_verification_context",
          {
            p_request_id: requestId,
            p_user_id: userId,
            p_session_id: sessionId,
          },
        );
        const context = Array.isArray(rows) ? rows[0] : null;
        if (!context || context.request_status !== "pending" ||
            typeof context.expires_at !== "string" || Date.parse(context.expires_at) <= Date.now() ||
            typeof context.challenge_hash !== "string" ||
            !["first_device_setup", "replacement"].includes(String(context.request_mode)) ||
            typeof context.public_key !== "string") {
          return respond(410, { success: false, error: "This phone recovery request has expired. Start again." });
        }
        if (await sha256Hex(challenge) !== context.challenge_hash) {
          return respond(403, { success: false, error: "The phone recovery challenge is not valid." });
        }
        await requireValidSignature(context.public_key, signed, challenge);
        const marked = await rpcData<boolean>(adminClient, "beekeep_internal_device_mark_request_verified", {
          p_request_id: requestId,
          p_user_id: userId,
          p_session_id: sessionId,
          p_challenge_hash: context.challenge_hash,
        });
        if (!marked) return respond(410, { success: false, error: "This phone recovery request has expired. Start again." });
        const deviceId = await rpcData<string>(adminClient, "beekeep_internal_device_recover_request", {
          p_request_id: requestId,
          p_user_id: userId,
          p_session_id: sessionId,
          p_challenge_hash: context.challenge_hash,
        });
        return respond(200, { success: true, status: "approved", device_id: deviceId });
      }

      default:
        return respond(400, { success: false, error: "Unsupported phone approval action." });
    }
  } catch (error) {
    if (error instanceof Error && error.message.startsWith("Invalid ")) {
      return respond(400, { success: false, error: error.message });
    }
    if (error instanceof Error &&
        (error.message.includes("not approved") || error.message.includes("Only the trusted"))) {
      return respond(403, { success: false, error: error.message });
    }
    console.error("BeeKeep device access request failed:", error instanceof Error ? error.message : "unknown");
    return respond(400, { success: false, error: error instanceof Error ? error.message : "The phone approval request failed." });
  }
});
