# BeeKeep single-session device safety

## Goal

Allow exactly one active BeeKeep Cloud session per account. A new phone may sign in with the account credentials, but it cannot read or change BeeKeep cloud data until the existing trusted phone approves the replacement. When the new phone finishes the handover, the old phone is signed out and keeps its local records.

Each workspace member still uses their own BeeKeep account and their own active session. Guest mode remains local-only.

## The simple phone handover

### New phone (B)

1. The user signs in with email/password or Google.
2. BeeKeep creates a five-minute request using the Supabase JWT `session_id`. This is the server-issued identity for the current Auth session; the client does not invent an installation ID or send a device key.
3. B shows one "Waiting for approval" screen. It has no cloud data access while pending.
4. When approved, B automatically completes the handover. The Edge Function revokes every other Supabase Auth session while preserving B's session, then activates B in the private trusted-session registry.
5. BeeKeep verifies that B is now the active session and resumes normal account, workspace, and sync startup.

### Existing trusted phone (A)

1. When BeeKeep is open and online, A sees an in-app approval dialog containing the requesting phone's model name.
2. The user chooses Approve or Deny.
3. Approve authorizes B's request but does not sign A out prematurely. A remains the only active trusted phone until B actually completes finalization.
4. Once B finalizes, Supabase revokes A's Auth session. The server's existing trusted-session checks immediately deny A's cloud access, even while an already-issued access token has not yet expired.
5. When A next checks authorization, BeeKeep stops Realtime, cancels WorkManager sync, clears A's local Auth session, and enters local-only mode.

The request alert is an in-app foreground check. BeeKeep does not currently have Firebase Cloud Messaging configured, so this is not a guaranteed push notification when the old app is closed.

## Recovery

If the old phone is lost or unavailable, the signed-in user can verify a fresh six-digit code sent to the account's confirmed email address. The Edge Function checks the recent email-OTP authentication method and uses the same finalization process. First-phone enrollment uses this same email verification path.

Email recovery is intentionally a fallback: access to the account email can replace a lost phone, so the email account should be protected.

## Server authorization

- The JWT `session_id` is the sole identity used to authorize the current Auth session.
- `public.beekeep_device_session_is_trusted()` checks that the current JWT session ID matches a live row in `auth.sessions` and the account's one active row in `beekeep_private.trusted_devices`.
- Existing restrictive RLS policies, inspection-photo Storage policy, and guarded account/workspace SECURITY DEFINER RPCs continue to call the same predicate.
- A replacement request is bound to the user's account and the requesting session ID. Only the current trusted session can approve or deny it.
- The replacement becomes trusted only after the Edge Function revokes other sessions and finalizes the database request.
- Unknown, pending, denied, expired, replaced, and revoked sessions remain denied cloud access.
- No client-supplied username, user metadata, device label, or preference is used as authorization proof.

## Local data safety

- Replacing a phone does not delete, migrate, or switch its Room database.
- The old phone stores a local-only marker, clears only its local Supabase Auth session, stops Realtime, and cancels background sync.
- Its personal records, apiaries, hive inspections, drafts, and pending local data remain on that phone.
- The old phone cannot upload unsynced changes after replacement. A later deliberate sign-in starts a new verification and the existing personal local-data review gate still controls attachment/upload.
- A phone that cannot reach the security service may keep using its local database, but cloud operations remain fail-closed.
- Workspace membership and cloud records are not deleted or modified by a device handover.

## Implementation status

### Existing security perimeter (already merged)

- Private trusted-session registry and one-active-session uniqueness rule.
- Restrictive device-session RLS policies on application data and inspection-photo Storage.
- Trusted-session checks on user-callable SECURITY DEFINER account/workspace RPCs and protected Edge Functions.
- Existing local-data review gate for personal records.

### Simplified handover (this branch / PR #145)

- Add a small session-bound request/approve/deny/finalize API.
- Remove Android Keystore device registration keys and ECDSA challenge signatures.
- Remove separate approval and finalization challenges.
- Use the same email-OTP recovery finalization for first setup and lost-phone recovery.
- Reduce the Android UI to one waiting screen and one approve/deny dialog.
- Keep the local Room database untouched when a phone is replaced.

## Rollout constraints

- Keep BeeKeep Production untouched. This branch is for review and automated testing; it has not been deployed to Supabase.
- Apply the additive handover migration to BeeKeep staging only after the matching Android client and CI are ready.
- Do not remove historical registry/request rows or data from the previous protocol during this remake.
- Physical two-phone verification is still required after installing the staging build: first setup, replacement approval, denial, recovery, retry after interruption, old-phone sign-out, offline local access, and confirmation that neither phone silently uploads the other's local data.
