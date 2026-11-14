# BeeKeep single-session device safety

## Goal

Allow exactly one active BeeKeep Cloud session per account. A new phone signs in with the account's normal password or Google provider, then verifies a six-digit code sent to the account's existing confirmed email address. Only after that code is verified does BeeKeep revoke the other Auth sessions and activate the new phone.

Each workspace member still uses their own BeeKeep account and their own active session. Guest mode remains local-only.

## Email-code phone replacement

### New phone (B)

1. The user signs in with the account's password or Google.
2. BeeKeep calls the authenticated device-access function. The database checks the current Supabase JWT `amr` claim and refuses to start a replacement from an email-OTP-only session when another trusted phone is active.
3. The request is bound to that initial Supabase Auth `session_id` and expires after five minutes. The app saves the request ID locally so it can recover from an interrupted handover.
4. BeeKeep sends a six-digit OTP using the account's already-confirmed email address. It does not allow creating a new account through this OTP request.
5. The user enters the code. Supabase Auth verifies it and creates a fresh Auth session.
6. The Edge Function verifies the current user, confirmed email, and recent OTP authentication method. A service-only database function rebinds the original password/Google request to this newly verified session.
7. The Edge Function begins finalization, revokes every other Supabase Auth session while preserving B's session, then activates B in the private trusted-session registry.
8. BeeKeep confirms B is the active trusted session and resumes normal account, workspace, and sync startup.

If B loses connectivity or the app closes after OTP verification, the saved request ID and verified state let BeeKeep retry the server-side bind/recovery/finalization safely. The server recovery operation is idempotent.

### Existing trusted phone (A)

1. A does not receive or approve a replacement prompt.
2. Until B verifies the email code and finalizes, A remains the active trusted phone.
3. After B finalizes, Supabase revokes A's Auth session. The server's trusted-session checks immediately deny A's cloud access, even while an already-issued JWT has not yet expired.
4. When A next checks authorization, BeeKeep stops Realtime, cancels WorkManager sync, clears A's local Auth session, and enters local-only mode.

This is not a guaranteed push sign-out when A's app is closed or offline. The server denies A immediately; the local app screen changes when it next checks authorization.

## Security boundaries

- The code is sent only to the signed-in account's existing confirmed email address.
- The initial request must come from a password, Google OAuth, or other accepted primary authentication method. An email OTP alone cannot start replacement when a trusted phone exists.
- The six-digit code is verified by Supabase Auth, is single-use, and is governed by the project's Auth OTP expiry and email rate limits.
- The request is bound to the original authenticated session before the code is sent. After successful OTP verification, a service-only RPC binds it to the new OTP session.
- The Edge Function validates the bearer through Supabase Auth `getUser()`, checks the current JWT claims, and checks the recent OTP AMR entry before it can bind or recover a request.
- Only after verification does the Edge Function revoke other Auth sessions and activate the verified session. Wrong or expired codes do not alter the trusted device.
- `public.beekeep_device_session_is_trusted()` remains the server-side authorization predicate. Existing restrictive RLS policies, inspection-photo Storage policy, and guarded account/workspace SECURITY DEFINER RPCs continue to use it.
- The six-digit code is an email possession check, not phishing-resistant MFA. Protecting the account's email inbox remains important.

## Local data safety

- Replacing a phone does not delete, migrate, or switch its Room database.
- The old phone stores a local-only marker, clears only its local Supabase Auth session, stops Realtime, and cancels background sync.
- Its personal records, apiaries, hive inspections, drafts, and pending local data remain on that phone.
- The old phone cannot upload unsynced changes after replacement. A later deliberate sign-in starts a new email-code verification and the existing personal local-data review gate still controls attachment/upload.
- A phone that cannot reach the security service may keep using its local database, but cloud operations remain fail-closed.
- Workspace membership and cloud records are not deleted or modified by a device handover.

## Implementation status

### Existing security perimeter (already merged)

- Private trusted-session registry and one-active-session uniqueness rule.
- Restrictive device-session RLS policies on application data and inspection-photo Storage.
- Trusted-session checks on user-callable SECURITY DEFINER account/workspace RPCs and protected Edge Functions.
- Existing local-data review gate for personal records.

### Email-code handover (this branch / PR)

- Remove phone-to-phone approval UI and endpoints from the Android client.
- Require password/Google authentication before the replacement request is created.
- Send and verify the existing six-digit Supabase Auth email OTP.
- Rebind the request to the fresh verified OTP session with a service-only RPC.
- Revoke other Auth sessions only after email OTP verification.
- Make recovery/finalization safe to retry after an interrupted app or network transition.
- Preserve the old phone's local database and disable its cloud access after replacement.

## Staging rollout

- Applied `20261114171221_beekeep_email_code_device_handover` to the Supabase project named BeeKeep.
- Deployed `beekeep-device-access` at version 5 with `verify_jwt = false`; the function validates the current user through Auth `getUser()`, requires a recent email OTP for recovery, binds the request to that verified session, then revokes other sessions.
- Verified authenticated users can begin requests, anonymous users cannot, and only `service_role` can bind the verified session. The old phone approve/deny RPCs are no longer executable by authenticated users.
- BeeKeep Production remains untouched.
- The existing trusted-session registry, RLS policies, and local-data safeguards remain in place; no hive, apiary, workspace, or account records are deleted by this change.

## Remaining physical verification

After installing the staging debug APK, test successful OTP replacement, incorrect/expired code, passwordless OTP-only attempt while another phone is trusted, first-device setup, resend window, interrupted finalization retry, old-phone local sign-out, offline local access, and confirmation that neither phone silently uploads the other's local data.
