# Trusted-device access: audit and implementation plan

## Goal

Allow one trusted Android installation per BeeKeep account at a time. A user may replace their phone, but a second installation must not gain access merely by knowing the account password. The current trusted installation should approve a replacement when available. Workspace teammates continue to use their own BeeKeep accounts and are not affected by this per-account device limit.

Guest mode remains local-only. Hive records and drafts already stored on a phone must not be deleted as a side effect of device authorization or revocation.

## Pre-rollout baseline audit (main, 2026-11-07)

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

#### 2A — Trusted-session registry (complete)

- Add private registry tables for trusted installations and expiring access requests.
- Add a reusable server predicate that binds the JWT `session_id` to an active device row and a live `auth.sessions` row.
- Add a fail-closed guard helper for later RPCs.
- Add SQL tests for matching, unknown, cross-account, and revoked sessions.
- This first migration deliberately does not attach the guard to live data policies, so it cannot lock out existing app versions.

#### 2B — Approval API (complete)

- Add pre-trust request creation, session-bound status, and trusted-phone pending-request APIs.
- Add short-lived key-proof and approval challenges; expose approval/recovery/rebind operations only through service-role RPCs.
- Verify ECDSA signatures in a server Edge Function before service RPCs can mark a request verified or replace the trusted device.
- Keep the RLS/Storage lockout switch separate until the Android client is ready.

#### 2C — Access enforcement (complete; deployed to staging)

- A restrictive trusted-session policy now gates all 19 public RLS tables and the `inspection-photos` Storage bucket while preserving existing user-ownership and workspace-role policies.
- The migration adds an explicit trusted-device guard to 24 user-callable SECURITY DEFINER account/workspace RPCs and direct membership/entitlement helpers.
- The staging entitlement simulator and support-admin Edge Functions verify the caller's Auth session against the trusted-device registry before using service-role operations.
- PostgreSQL regression coverage verifies trusted owner/member access, denial for unregistered/replaced sessions, private-record and photo denial, guarded RPC denial, and the allowed pre-trust enrolment path.

#### 2D — Deployment and verification (complete on BeeKeep staging)

- Applied migrations `20261107043551 beekeep_trusted_device_foundation`, `20261107043558 beekeep_trusted_device_request_api`, and `20261107114016 beekeep_trusted_device_server_enforcement` to project `nnmcwsdunpwdktexgivr`.
- Deployed `beekeep-device-access` version 2, `beekeep-debug-entitlement` version 3, and `beekeep-admin-hive-limits` version 4. All three have JWT verification enabled.
- Verified 19 public RLS tables have exactly one new restrictive device policy each, plus one restrictive policy on Storage objects for `inspection-photos`.
- Verified 24 public SECURITY DEFINER account/workspace functions include the trusted-device guard; authenticated enrolment/status calls remain available, while internal approval RPCs remain inaccessible to authenticated clients.
- Ran the complete trusted-device enforcement regression SQL against the live staging database successfully. The test transaction rolled back, and a follow-up query confirmed no test users, documents, or Storage objects remained.
- The CI Storage fixture was aligned with Supabase's deployed `storage.objects.owner_id text` type in PR #139. No production project was changed.
- No signed round-trip using two physical phones has been completed yet; that remains part of Stage 5.

### Stage 3 — Android enrolment and session gate

#### 3A — Key store and API client (complete)

- Generate and persist a non-exportable P-256 signing key in Android Keystore.
- Derive the installation fingerprint from the encoded public key and sign server challenges with SHA-256/ECDSA.
- Add a typed Kotlin client for begin/status/list/complete/approve/deny/recovery Edge Function actions.
- Add JVM cryptography tests for signature verification, challenge binding, and fingerprint formatting.

#### 3B — Sign-in and foreground approval state machine (complete; merged to main)

- The Android client verifies the current installation before restoring a cloud account or allowing sync/workspace operations.
- First enrolment and lost-phone recovery use a fresh six-digit email OTP; a replacement phone requires approval from the existing trusted phone; same-phone reauthentication can rebind through its Keystore key.
- The verification gate supports pending, denied, expired, replaced, offline-local, and sign-out states.
- The trusted phone polls for verified replacement requests and can approve/deny with a signed Keystore challenge. This is an in-app foreground path; FCM/background push is not configured yet.
- Merged in PR #137 at `c1f39905ad39a2e0533171f57212e0686c40c2aa`.

#### 3C — Background and revocation handling (complete)

- Startup restoration verifies the trusted-device session before account setup, workspace restoration, or realtime starts.
- Foreground sync and WorkManager both pass through the gateway's trusted-session check; table reads/writes, Storage, and guarded SECURITY DEFINER RPCs are additionally protected by the deployed server policy.
- A replaced phone transitions to local-only mode and keeps its Room records/drafts; cloud sync and workspace access are denied.
- Authorization is polled while BeeKeep is open. Realtime FCM approval and a signed real-device request/approval test remain outstanding in Stage 5.

### Stage 4 — Trusted-phone approval and recovery (core Android/API work complete)

- Pending request screen, approve/deny controls, signed challenges, atomic replacement, same-phone rebind, email recovery, and expiry messaging are merged and deployed.
- Physical two-phone validation, rate-limit review, and recovery-flow verification remain part of Stage 5 validation.

### Stage 5 — Notifications and two-phone validation (remaining)

- Add FCM only after BeeKeep has a Firebase project/configuration and server credentials; the current approval alert is foreground polling, not a push notification.
- Validate on two physical Android phones: first enrolment, normal sign-in, replacement request, allow, deny, expiry, lost-phone email recovery, same-phone rebind, revoked-phone reconnect, offline local editing, WorkManager, personal data, team data, and inspection-photo Storage.
- Verify real-world resend/recovery rate limits and the account recovery experience before enabling this in a production release.

## Rollout and safety constraints

- Staging enforcement is deployed only after the matching migration and regression tests passed in CI; keep BeeKeep Production untouched until an explicit production rollout is planned.
- Do not automatically trust a new session just because it has valid email/password credentials.
- Do not revoke an old phone until the replacement approval transaction succeeds.
- Do not clear Room databases, drafts, or local attachment data when a device is denied or replaced.
- Fail closed for cloud access when device status cannot be verified; keep local/offline access available.
- Keep the change compatible with the current Supabase free plan; do not depend on the Pro-only Supabase single-session dashboard setting.
