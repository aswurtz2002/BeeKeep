// Private support-admin endpoint for BeeKeep per-account Ultimate hive-limit exceptions.
// The service-role key stays in Supabase. Every mutating request rechecks the trusted
// app_metadata flag on the authenticated user before touching the internal override table.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function respond(status: number, body: Record<string, unknown>) {
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

function publicKeyFromEnvironment(): string {
  const keysText = Deno.env.get("SUPABASE_PUBLISHABLE_KEYS");
  if (keysText) {
    try {
      const keys = JSON.parse(keysText) as Record<string, string>;
      if (keys.default) return keys.default;
    } catch {
      // Fall back to the platform's legacy environment variables below.
    }
  }
  return Deno.env.get("SUPABASE_ANON_KEY") ??
    Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return respond(405, { success: false, error: "Method not allowed." });

  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const publicKey = publicKeyFromEnvironment();
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  if (!supabaseUrl || !publicKey || !serviceRoleKey) {
    return respond(503, { success: false, error: "Support admin tools are not configured on this server." });
  }

  const token = (req.headers.get("Authorization") ?? "").match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) return respond(401, { success: false, allowed: false, error: "Sign in to BeeKeep Cloud first." });

  const userClient = createClient(supabaseUrl, publicKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: authError } = await userClient.auth.getUser(token);
  if (authError || !userData.user) {
    return respond(401, { success: false, allowed: false, error: "Your cloud session is no longer valid. Sign in again." });
  }

  const isSupportAdmin = userData.user.app_metadata?.beekeep_support_admin === true;
  let body: {
    action?: unknown;
    email?: unknown;
    hive_limit?: unknown;
    reason?: unknown;
  };
  try {
    body = await req.json();
  } catch {
    return respond(400, { success: false, error: "Expected a JSON body." });
  }

  const action = typeof body.action === "string" ? body.action.trim().toLowerCase() : "";
  if (action === "check") {
    return respond(200, { success: true, allowed: isSupportAdmin });
  }
  if (!isSupportAdmin) {
    return respond(403, { success: false, allowed: false, error: "This BeeKeep account is not authorized for support-admin tools." });
  }
  if (!["lookup", "set", "clear"].includes(action)) {
    return respond(400, { success: false, error: "Unsupported action." });
  }

  const email = typeof body.email === "string" ? body.email.trim() : "";
  if (!email || email.length > 254 || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    return respond(400, { success: false, error: "Enter a valid account email." });
  }

  let customLimit: number | null = null;
  let reason: string | null = null;
  if (action === "set") {
    customLimit = body.hive_limit as number;
    reason = typeof body.reason === "string" ? body.reason.trim() : "";
    if (!Number.isInteger(customLimit) || customLimit < 10000 || customLimit > 1000000) {
      return respond(400, { success: false, error: "Custom hive limits must be between 10,000 and 1,000,000." });
    }
    if (reason.length < 3 || reason.length > 500) {
      return respond(400, { success: false, error: "Enter a reason between 3 and 500 characters." });
    }
  }

  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data, error } = await admin.rpc("beekeep_admin_manage_hive_limit_override", {
    p_action: action,
    p_email: email,
    p_hive_limit: customLimit,
    p_reason: reason,
    p_actor_id: userData.user.id,
  });

  if (error) {
    if (error.code === "P0002") return respond(404, { success: false, error: "No BeeKeep account was found with that exact email." });
    if (error.code === "22023") return respond(400, { success: false, error: error.message });
    // Do not return internal query details or database metadata to the client.
    console.error("Support-admin hive-limit RPC failed:", error.code ?? "unknown");
    return respond(500, { success: false, error: "The support-admin request failed. Check the server logs." });
  }

  const row = Array.isArray(data) ? data[0] : null;
  if (!row) return respond(404, { success: false, error: "No BeeKeep account was returned." });
  return respond(200, { success: true, ...row });
});
