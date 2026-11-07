# Trusted-device access: audit and implementation plan

## Goal

Allow one trusted Android installation per BeeKeep account at a time. A user may replace their phone, but a second installation must not gain access merely by knowing the account password. The current trusted installation should approve a replacement when available. Workspace teammates continue to use their own BeeKeep accounts and are not affected by this per-account device limit.

Guest mode remains local-only. Hive records and drafts already stored on a phone must not be deleted as a side effect of device authorization or revocation.

## Current-state audit (main, 2026-11-07)

### Authentication and session lifecycle

- Email/password sign-in in `SupabaseGateway.signIn` creates a normal Supabase Auth session.
- Google ID-token sign-in also creates a normal Supabase Auth session.
- Startup restores the persisted Auth session before account setup and cloud synchronization.
- `CloudSyncWorker` constructs a gateway that reuses the process-persisted Supabase session for background synchronization.
- Supabase access-token JWTs carry a `session_id`; the Supabase project currently permits multiple simultaneous sessions by default.
- BeeKeep's `device_id` is a UUID in ordinary app SharedPreferences and is included in document sync metadata. It is not a credential and must not be used as proof that an installation is trusted.

### Data access paths that need the device gate

- Personal records: `beekeep_documents`, personal sync state/version tables, sync RPCs, and document conflict lookups.
- Shared workspace records: `beekeep_workspace_documents`, workspace membership, sync state/version tables, action settings, and action-log reads/writes/RPCs.
- Account/workspace setup and metadata RPCs, including workspace listing, username retrieval, entitlements, invitation/member management, and workspace mutations.
- Private Storage bucket `inspection-photos`. Its existing four object policies check the first path segment against `auth.uid()`, but do not check an approved device.
- Edge Functions must be audited separately. The staging project currently exposes `beekeep-debug-entitlement` and `beekeep-admin-hive-limits`.

All 19 inspected public application tables have RLS enabled. Existing policies authorize by account ownership or workspace membership/role; none checks a trusted device or the JWT `session_id`. The photo bucket's policies similarly authorize by account folder only.

Some user-callable workspace/account RPCs are `SECURITY DEFINER`. Because those functions can bypass table RLS, they must perform an explicit trusted-session check themselves. Trigger-only and service-role-only helper functions should not be indiscriminately modified.

### Notifications

The Android app currently has no Firebase Messaging dependency, messaging service, FCM token registration, or Firebase configuration file. A true background push approval flow therefore needs a separate FCM setup. Until that configuration exists, the first usable approval channel can be an in-app request visible to the trusted phone while online, with email verification as recovery/fallback. This does not claim to deliver push notifications while BeeKeep is closed.

## Target security model

### Installation identity

- Generate a non-exportable ECDSA P-256 key pair in Android Keystore for each app installation.
- Keep the private key on the phone. Send only the public key to BeeKeep.
- Derive the installation identifier from the public key fingerprint; do not trust a caller-supplied SharedPreferences UUID.
- Bind the approved installation to the Supabase Auth `session_id` that is currently authorized for the account.

### Server authorization

- Add a private trusted-device registry and pending device-request records.
- A device request begins in a pending state and expires after five minutes.
- RLS and Storage policies must deny pending, denied, expired, replaced, and otherwise unknown sessions from all BeeKeep user data.
- The active phone proves possession of its private key by signing a short-lived server challenge before it can approve a replacement.
- Approval is atomic: the new installation/session becomes the one active installation; the previous installation/session is revoked for cloud access.
- Every sensitive user-facing `SECURITY DEFINER` RPC and Edge Function must enforce the same active-device check because such paths may bypass ordinary RLS.
- Device authorization is based on server records and the JWT `session_id`, never editable user metadata or a client-only flag.

### Login and replacement experience

1. The user enters email/password or signs in with Google. Supabase may issue a session, but that session remains unable to access BeeKeep records until device authorization succeeds.
2. A registered phone receives the pending request in BeeKeep when it is online and can approve or deny it after a signed challenge.
3. If the old phone is unavailable, email verification provides a recovery path. The first device enrolment after rollout also requires email verification; it must not silently award trust to whichever pre-existing session happens to register first.
4. Once approved, the replacement phone can sync. The replaced phone loses cloud access when it next attempts an operation; its offline local data is retained and is never silently uploaded after revocation.
5. Each teammate continues to have their own account and their own trusted phone, so shared workspace access remains account-based and role-controlled.

Email recovery is not equivalent to approval from an uncompromised old phone: anyone who controls the account email may be able to recover access. A later FCM notification can provide better visibility, but does not replace server-side authorization.

## Implementation stages

### Stage 1 — Audit and design

- Completed: trace email/password, Google, restored-session, foreground-sync, background-sync, direct table/RPC, Storage, and current policy paths.
- Completed: document the security model and rollout constraints.

### Stage 2 — Backend foundation

#### 2A — Trusted-session registry (in progress)

- Add private registry tables for trusted installations and expiring access requests.
- Add a reusable server predicate that binds the JWT `session_id` to an active device row and a live `auth.sessions` row.
- Add a fail-closed guard helper for later RPCs.
- Add SQL tests for matching, unknown, cross-account, and revoked sessions.
- This first migration deliberately does not attach the guard to live data policies, so it cannot lock out existing app versions.

#### 2B — Access enforcement (not started)

- Add restrictive device authorization to personal/workspace table policies and photo Storage policies.
- Add explicit guards to user-callable `SECURITY DEFINER` functions and sensitive Edge Functions.
- Add SQL security tests proving a pending/replaced device cannot read or write personal records, shared workspace records, action data, or private photos, while a trusted device and another legitimate workspace member still can.

#### 2C — Deployment and verification (not started)

- Apply and verify the fully coordinated database migrations only on BeeKeep staging project `nnmcwsdunpwdktexgivr`; never change BeeKeep Production as part of this work.

### Stage 3 — Android enrolment and session gate

- Generate and persist the installation key using Android Keystore.
- Route email/password and Google sign-in through the same device-authorization state machine.
- Keep pending sessions out of account setup, cloud sync, realtime, and cloud-backed screens.
- Gate WorkManager and startup session restoration in the same way.
- Preserve local/offline records and drafts on all device-lock states.

### Stage 4 — Trusted-phone approval and recovery

- Add a pending request screen and approve/deny controls.
- Require signed challenges and atomically replace the active installation/session.
- Add the email recovery path and expiry/rate-limit handling.
- Surface clear messages on a phone whose cloud authorization has been replaced.

### Stage 5 — Notifications and two-phone validation

- Add FCM only after BeeKeep has a Firebase project/configuration and server credentials.
- Test on two physical Android phones: initial enrolment, normal sign-in, second-phone pending state, allow, deny, expiry, lost-phone email recovery, revoked phone reconnect, offline local editing, background worker, personal records, team records, and Storage access.

## Rollout and safety constraints

- Do not make live database changes before the matching migration and security tests are in the repository.
- Do not automatically trust a new session just because it has valid email/password credentials.
- Do not revoke an old phone until the replacement approval transaction succeeds.
- Do not clear Room databases, drafts, or local attachment data when a device is denied or replaced.
- Fail closed for cloud access when device status cannot be verified; keep local/offline access available.
- Keep the change compatible with the current Supabase free plan; do not depend on the Pro-only Supabase single-session dashboard setting.
