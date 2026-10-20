// STAGING TEST ONLY. Do not deploy this function to production.
// It lets the debug purchase simulator exercise server-side workspace checks without
// pretending that local plan selection is a real Google Play purchase.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "npm:@supabase/supabase-js@2";

const STAGING_PROJECT_REF = "nnmcwsdunpwdktexgivr";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type, x-client-info",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
};

function respond(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json", "Cache-Control": "no-store" },
  });
}

Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (req.method !== "POST") return respond(405, { success: false, error: "Method not allowed." });

  const supabaseUrl = Deno.env.get("SUPABASE_URL") ?? "";
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ?? "";
  const publicKey = Deno.env.get("SUPABASE_ANON_KEY") ?? Deno.env.get("SUPABASE_PUBLISHABLE_KEY") ?? "";
  let hostname = "";
  try {
    hostname = new URL(supabaseUrl).hostname;
  } catch {
    return respond(503, { success: false, error: "Supabase URL is not configured." });
  }

  // Defense in depth: even if someone deploys this source elsewhere, it never grants there.
  if (hostname !== `${STAGING_PROJECT_REF}.supabase.co`) {
    return respond(404, { success: false, error: "This test endpoint is available only in BeeKeep-Staging." });
  }
  if (!serviceRoleKey || !publicKey) {
    return respond(503, { success: false, error: "Staging test endpoint is not configured." });
  }

  const authorization = req.headers.get("Authorization") ?? "";
  const token = authorization.match(/^Bearer\s+(.+)$/i)?.[1];
  if (!token) return respond(401, { success: false, error: "Sign-in is required." });

  const userClient = createClient(supabaseUrl, publicKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: userData, error: authError } = await userClient.auth.getUser(token);
  if (authError || !userData.user) {
    return respond(401, { success: false, error: "Your BeeKeep Cloud session is no longer valid. Sign in again." });
  }

  let body: { plan?: unknown };
  try {
    body = await req.json();
  } catch {
    return respond(400, { success: false, error: "Expected a JSON body." });
  }
  const plan = body.plan;
  const durations: Record<string, number> = {
    free: 0,
    pro_monthly: 30,
    pro_yearly: 365,
    ultimate_monthly: 30,
    ultimate_yearly: 365,
  };
  if (typeof plan !== "string" || !(plan in durations)) {
    return respond(400, { success: false, error: "Unknown debug plan." });
  }

  // This elevated client exists only inside the isolated staging function. Service-role
  // credentials never leave Supabase and user-provided metadata is never trusted.
  const admin = createClient(supabaseUrl, serviceRoleKey, {
    auth: { persistSession: false, autoRefreshToken: false },
  });
  const { data: currentUserResult, error: readError } = await admin.auth.admin.getUserById(userData.user.id);
  if (readError || !currentUserResult.user) {
    return respond(500, { success: false, error: "Could not load the staging account." });
  }
  const currentMetadata = currentUserResult.user.app_metadata ?? {};
  const expiry = plan === "free"
    ? null
    : new Date(Date.now() + durations[plan] * 24 * 60 * 60 * 1000).toISOString();
  const updatedMetadata = {
    ...currentMetadata,
    beekeep_plan: plan === "free" ? null : plan,
    beekeep_subscription_expires_at: expiry,
  };
  const { error: updateError } = await admin.auth.admin.updateUserById(userData.user.id, {
    app_metadata: updatedMetadata,
  });
  if (updateError) {
    return respond(500, { success: false, error: "Could not update the staging test entitlement." });
  }

  return respond(200, {
    success: true,
    active: plan === "ultimate_monthly" || plan === "ultimate_yearly",
    plan,
  });
});
