import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

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

function readKeySet(name: string, legacyName: string): string {
  const value = Deno.env.get(name);
  if (value) {
    try {
      const parsed = JSON.parse(value) as Record<string, string>;
      if (typeof parsed.default === "string" && parsed.default.length > 0) return parsed.default;
    } catch {
      // Keep compatibility with the legacy environment variable.
    }
  }
  return Deno.env.get(legacyName) ?? "";
}

function decodeJwtPayload(token: string): JsonRecord {
  const parts = token.split(".");
  if (parts.length !== 3) throw new Error("Invalid access token.");
  const normalized = parts[1].replace(/-/g, "+").replace(/_/g, "/");
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, "=");
  const binary = atob(padded);
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0));
  const value: unknown = JSON.parse(new TextDecoder().decode(bytes));
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("Invalid access token claims.");
  }
  return value as JsonRecord;
}

function isUuid(value: unknown): value is string {
  return typeof value === "string" &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
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
  const rpcClient = client as RpcClient;
  const { data, error } = await rpcClient.rpc(functionName, args);
  if (error) {
    console.error("Device access RPC failed:", functionName, error.code ?? "unknown");
    throw new Error("The phone approval request could not be completed.");
  }
  return data as T;
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return respond(405, { success: false, error: "Method not allowed." });

  try {
    const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
    const publicKey = readKeySet("SUPABASE_PUBLISHABLE_KEYS", "SUPABASE_ANON_KEY") ||
      Deno.env.get("SUPABASE_PUBLISHABLE_KEY") || "";
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
        const label = requiredString(body.device_label, "device label", 80);
        const request = await rpcData<unknown>(
          userClient,
          "beekeep_device_simple_begin_request",
          { p_device_label: label },
        );
        return respond(200, { success: true, request });
      }

      case "status": {
        const request = await rpcData<unknown>(
          userClient,
          "beekeep_device_get_request_status",
          {},
        );
        return respond(200, { success: true, request });
      }

      case "list": {
        const requests = await rpcData<unknown>(
          userClient,
          "beekeep_device_list_pending_requests",
          {},
        );
        return respond(200, { success: true, requests });
      }

      case "approve":
      case "deny": {
        if (!isUuid(requestId)) {
          return respond(400, { success: false, error: "Invalid phone request." });
        }
        if (action === "approve") {
          const status = await rpcData<string>(
            userClient,
            "beekeep_device_simple_approve_request",
            { p_request_id: requestId },
          );
          return respond(200, { success: true, status });
        }
        const denied = await rpcData<boolean>(
          userClient,
          "beekeep_device_simple_deny_request",
          { p_request_id: requestId },
        );
        return respond(200, { success: true, status: denied ? "denied" : "expired" });
      }

      case "finalize": {
        if (!isUuid(requestId)) {
          return respond(400, { success: false, error: "Invalid phone request." });
        }
        const contextRows = await rpcData<Array<JsonRecord>>(
          adminClient,
          "beekeep_internal_device_simple_finalize_context",
          {
            p_request_id: requestId,
            p_user_id: userId,
            p_session_id: sessionId,
          },
        );
        const context = Array.isArray(contextRows) ? contextRows[0] : null;

        // A lost response after activation is safe to retry.
        if (context?.request_status === "approved") {
          const trusted = await rpcData<boolean>(
            userClient,
            "beekeep_device_session_is_trusted",
            {},
          );
          if (trusted) return respond(200, { success: true, status: "approved" });
          return respond(409, { success: false, error: "This phone is no longer the active BeeKeep session." });
        }

        if (!context ||
            !["approved_pending_finalize", "finalizing"].includes(String(context.request_status))) {
          return respond(409, { success: false, error: "This phone is still waiting for approval." });
        }

        const finalizationArgs = {
          p_request_id: requestId,
          p_user_id: userId,
          p_session_id: sessionId,
        };
        const begun = await rpcData<boolean>(
          adminClient,
          "beekeep_internal_device_simple_begin_finalization",
          finalizationArgs,
        );
        if (!begun) {
          return respond(409, { success: false, error: "BeeKeep could not start the phone handover. Retry." });
        }

        const abortFinalization = async () => {
          try {
            await rpcData<boolean>(
              adminClient,
              "beekeep_internal_device_simple_abort_finalization",
              finalizationArgs,
            );
          } catch {
            console.error("Phone handover cleanup failed; the finalization lease can be retried.");
          }
        };

        // Use the replacement phone's own JWT. Keep this session and revoke every other
        // Auth session. The RLS session predicate blocks any still-unexpired old JWT too.
        try {
          const { error } = await adminClient.auth.admin.signOut(bearer, "others");
          if (error) {
            await abortFinalization();
            return respond(503, {
              success: false,
              error: "BeeKeep couldn't sign out the previous phone yet. No new phone was activated. Retry.",
            });
          }
        } catch {
          await abortFinalization();
          return respond(503, {
            success: false,
            error: "BeeKeep couldn't sign out the previous phone yet. No new phone was activated. Retry.",
          });
        }

        try {
          const deviceId = await rpcData<string>(
            adminClient,
            "beekeep_internal_device_simple_finalize_request",
            finalizationArgs,
          );
          return respond(200, { success: true, status: "approved", device_id: deviceId });
        } catch {
          await abortFinalization();
          console.error("Old sessions were revoked but the new trusted session needs activation retry.");
          return respond(503, {
            success: false,
            error: "The previous phone was signed out, but BeeKeep couldn't finish activating this phone. Retry here.",
          });
        }
      }

      case "recover": {
        if (!isUuid(requestId)) {
          return respond(400, { success: false, error: "Invalid phone request." });
        }
        if (!recentEmailOtp(claims)) {
          return respond(403, {
            success: false,
            error: "Verify the six-digit email security code before recovering this phone.",
          });
        }
        if (!userData.user.email_confirmed_at) {
          return respond(403, {
            success: false,
            error: "Confirm your BeeKeep account email before recovering a phone.",
          });
        }
        await rpcData<boolean>(
          adminClient,
          "beekeep_internal_device_simple_recover_request",
          {
            p_request_id: requestId,
            p_user_id: userId,
            p_session_id: sessionId,
          },
        );
        return respond(200, { success: true, status: "pending_finalization" });
      }

      default:
        return respond(400, { success: false, error: "Unsupported phone approval action." });
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "The phone approval request failed.";
    if (message.includes("already being completed")) {
      return respond(409, { success: false, error: message });
    }
    if (message.includes("expired") || message.includes("no longer waiting")) {
      return respond(410, { success: false, error: message });
    }
    if (message.includes("Only the currently trusted") || message.includes("not approved")) {
      return respond(403, { success: false, error: message });
    }
    console.error("BeeKeep device access request failed:", message);
    return respond(400, { success: false, error: message });
  }
});
