-- Complete trusted-device replacement only after the new phone finalizes with its own session.
-- Approval/recovery/rebind revoke the previous trusted binding first. The new binding is
-- inserted only after the Edge Function revokes all other Auth sessions, preserving this one.
begin;

alter table beekeep_private.device_access_requests
    drop constraint if exists device_access_requests_request_status_check;

alter table beekeep_private.device_access_requests
    add constraint device_access_requests_request_status_check
    check (request_status in (
        'pending',
        'approved_pending_finalize',
        'finalizing',
        'approved',
        'denied',
        'expired',
        'replaced'
    ));

alter table beekeep_private.device_access_requests
    add column if not exists finalization_challenge_hash text,
    add column if not exists finalization_challenge_expires_at timestamptz,
    add column if not exists finalization_started_at timestamptz,
    add column if not exists finalized_at timestamptz;

alter table beekeep_private.device_access_requests
    drop constraint if exists device_access_requests_finalization_challenge_check;

alter table beekeep_private.device_access_requests
    add constraint device_access_requests_finalization_challenge_check
    check (
        (finalization_challenge_hash is null and finalization_challenge_expires_at is null)
        or (
            finalization_challenge_hash is not null
            and finalization_challenge_hash ~ '^[0-9a-f]{64}

create index if not exists device_access_requests_finalization_user_idx
    on beekeep_private.device_access_requests (user_id, finalization_started_at desc)
    where request_status in ('approved_pending_finalize', 'finalizing');

-- The trusted phone approves the replacement, but does not activate the new phone.
-- The previous device is revoked immediately; cloud access remains closed until B finishes.
create or replace function public.beekeep_internal_device_approve_request(
    p_request_id uuid,
    p_user_id uuid,
    p_approver_session_id uuid,
    p_approval_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
    approver_row beekeep_private.trusted_devices%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.approval_challenge_hash = p_approval_challenge_hash
      and r.approval_challenge_expires_at > pg_catalog.statement_timestamp()
    for update;

    if request_row.id is null then
        raise exception 'This phone request has expired or is no longer pending.'
            using errcode = 'P0002';
    end if;

    select d.* into approver_row
    from beekeep_private.trusted_devices d
    join auth.sessions s on s.id = d.session_id and s.user_id = d.user_id
    where d.user_id = p_user_id
      and d.session_id = p_approver_session_id
      and d.device_status = 'active'
      and d.revoked_at is null
      and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    for update of d;

    if approver_row.id is null then
        raise exception 'The approving BeeKeep phone is no longer trusted.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = request_row.session_id
          and s.user_id = request_row.user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The new phone sign-in session has expired. Start again.'
            using errcode = '42501';
    end if;

    update beekeep_private.trusted_devices d
    set device_status = 'replaced',
        revoked_at = pg_catalog.clock_timestamp(),
        revoked_reason = 'Replaced after trusted-phone approval; new phone must finalize'
    where d.id = approver_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = approver_row.id,
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null,
        finalized_at = null
    where r.id = request_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status = 'pending';

    return approver_row.id;
end;
$function$;

-- Same physical installation, fresh Auth session. Revoke the old session binding first;
-- the replacement binding is created only by the common finalization operation.
create or replace function public.beekeep_internal_device_rebind_session(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
    device_row beekeep_private.trusted_devices%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'pending'
      and r.request_mode = 'same_device_rebind'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.challenge_hash = p_challenge_hash
    for update;

    if request_row.id is null then
        raise exception 'This phone registration is no longer available.'
            using errcode = 'P0002';
    end if;

    select d.* into device_row
    from beekeep_private.trusted_devices d
    where d.user_id = p_user_id
      and d.key_fingerprint = request_row.key_fingerprint
      and d.public_key = request_row.public_key
      and d.device_status = 'active'
      and d.revoked_at is null
    for update;

    if device_row.id is null then
        raise exception 'This key is not registered as the trusted BeeKeep phone.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    update beekeep_private.trusted_devices d
    set device_status = 'replaced',
        revoked_at = pg_catalog.clock_timestamp(),
        revoked_reason = 'Same-phone session rebind; new session must finalize'
    where d.id = device_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = device_row.id,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null,
        finalized_at = null
    where r.id = request_row.id;

    return device_row.id;
end;
$function$;

-- Email recovery and first-device setup enter the same pending-finalization state.
-- A recent verified email OTP authorizes this replacement, but never activates a device
-- without the later Keystore proof and Auth-session cutover.
create or replace function public.beekeep_internal_device_recover_request(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'pending'
      and r.request_mode in ('first_device_setup', 'replacement')
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.challenge_hash = p_challenge_hash
    for update;

    if request_row.id is null then
        raise exception 'This phone registration has expired or is no longer pending.'
            using errcode = 'P0002';
    end if;

    if exists (
        select 1
        from beekeep_private.device_access_requests r
        join auth.sessions s on s.id = r.session_id and s.user_id = r.user_id
        where r.user_id = p_user_id
          and r.id <> request_row.id
          and r.request_status = 'finalizing'
          and r.finalization_started_at > pg_catalog.statement_timestamp() - interval '90 seconds'
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'Another phone is finishing its approval. Wait briefly and try again.'
            using errcode = '55P03';
    end if;

    if request_row.request_mode = 'first_device_setup'
       and exists (
           select 1
           from beekeep_private.trusted_devices d
           where d.user_id = p_user_id
             and d.device_status = 'active'
             and d.revoked_at is null
       ) then
        raise exception 'This account already has a trusted phone. Start a replacement request instead.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    update beekeep_private.trusted_devices d
    set device_status = 'replaced',
        revoked_at = pg_catalog.clock_timestamp(),
        revoked_reason = 'Replaced through verified email recovery; new phone must finalize'
    where d.user_id = p_user_id
      and d.device_status = 'active'
      and d.revoked_at is null;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status in ('pending', 'approved_pending_finalize', 'finalizing');

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = 'email_recovery',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null,
        finalized_at = null
    where r.id = request_row.id;

    return null;
end;
$function$;

-- The new phone asks for a short-lived challenge only for its own approved request.
-- A stuck finalization lease can be reclaimed after 90 seconds; a live operation cannot
-- be overwritten by a second recovery attempt.
create or replace function public.beekeep_device_issue_finalize_challenge(
    p_request_id uuid
)
returns text
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    session_claim text := (select auth.jwt() ->> 'session_id');
    caller_session_id uuid;
    request_row beekeep_private.device_access_requests%rowtype;
    challenge_value text;
begin
    if caller_id is null or session_claim is null
       or session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        raise exception 'Sign in to finish approving this phone.'
            using errcode = '42501';
    end if;
    caller_session_id := session_claim::uuid;

    perform 1 from auth.users u where u.id = caller_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = caller_id
      and r.session_id = caller_session_id
    for update;

    if request_row.id is null then
        raise exception 'This phone approval request could not be found.'
            using errcode = 'P0002';
    end if;

    if request_row.request_status = 'finalizing' then
        if request_row.finalization_started_at >
            pg_catalog.statement_timestamp() - interval '90 seconds' then
            raise exception 'This phone approval is already being finalized. Try again shortly.'
                using errcode = '55P03';
        end if;

        update beekeep_private.device_access_requests r
        set request_status = 'approved_pending_finalize',
            finalization_started_at = null,
            finalization_challenge_hash = null,
            finalization_challenge_expires_at = null
        where r.id = request_row.id;

        request_row.request_status := 'approved_pending_finalize';
    end if;

    if request_row.request_status <> 'approved_pending_finalize'
       or request_row.challenge_verified_at is null then
        raise exception 'This phone is not waiting for final approval.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = caller_session_id
          and s.user_id = caller_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    challenge_value := pg_catalog.encode(extensions.gen_random_bytes(32), 'base64');

    update beekeep_private.device_access_requests r
    set finalization_challenge_hash = pg_catalog.encode(
            extensions.digest(pg_catalog.convert_to(challenge_value, 'UTF8'), 'sha256'),
            'hex'
        ),
        finalization_challenge_expires_at = pg_catalog.clock_timestamp() + interval '2 minutes'
    where r.id = request_row.id;

    return challenge_value;
end;
$function$;

revoke all on function public.beekeep_device_issue_finalize_challenge(uuid)
    from public, anon;
grant execute on function public.beekeep_device_issue_finalize_challenge(uuid)
    to authenticated;

-- Service-only context: the Edge Function verifies B's fresh Keystore signature before
-- beginning any Auth session revocation.
create or replace function public.beekeep_internal_device_finalize_context(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
)
returns table (
    public_key text,
    finalization_challenge_hash text,
    finalization_challenge_expires_at timestamptz,
    request_status text,
    request_mode text
)
language sql
stable
security definer
set search_path = ''
as $function$
    select r.public_key, r.finalization_challenge_hash,
           r.finalization_challenge_expires_at, r.request_status, r.request_mode
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id;
$function$;

-- Serialize cutover start with account-level recovery and other approval operations.
create or replace function public.beekeep_internal_device_begin_finalization(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_finalization_challenge_hash text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
    for update;

    if request_row.id is null then
        raise exception 'This phone approval request could not be found.'
            using errcode = 'P0002';
    end if;

    if request_row.request_status = 'approved'
       and exists (
           select 1 from beekeep_private.trusted_devices d
           where d.user_id = p_user_id
             and d.session_id = p_session_id
             and d.key_fingerprint = request_row.key_fingerprint
             and d.device_status = 'active'
             and d.revoked_at is null
       ) then
        return true;
    end if;

    if request_row.request_status = 'finalizing'
       and request_row.finalization_started_at >
           pg_catalog.statement_timestamp() - interval '90 seconds' then
        raise exception 'This phone approval is already being finalized. Try again shortly.'
            using errcode = '55P03';
    end if;

    if request_row.request_status = 'finalizing' then
        update beekeep_private.device_access_requests r
        set request_status = 'approved_pending_finalize',
            finalization_started_at = null,
            finalization_challenge_hash = null,
            finalization_challenge_expires_at = null
        where r.id = request_row.id;
        request_row.request_status := 'approved_pending_finalize';
    end if;

    if request_row.request_status <> 'approved_pending_finalize'
       or request_row.finalization_challenge_hash is distinct from p_finalization_challenge_hash
       or request_row.finalization_challenge_expires_at <= pg_catalog.statement_timestamp() then
        raise exception 'The final phone approval challenge is invalid or expired.'
            using errcode = '42501';
    end if;

    if request_row.challenge_verified_at is null or not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The new phone sign-in session has expired. Start again.'
            using errcode = '42501';
    end if;

    if exists (
        select 1 from beekeep_private.trusted_devices d
        where d.user_id = p_user_id
          and d.device_status = 'active'
          and d.revoked_at is null
    ) then
        raise exception 'Another phone is still active. Refresh the request and try again.'
            using errcode = '42501';
    end if;

    update beekeep_private.device_access_requests r
    set request_status = 'finalizing',
        finalization_started_at = pg_catalog.clock_timestamp()
    where r.id = request_row.id;

    return true;
end;
$function$;

-- Only this function creates the replacement's active trusted-device row. It is called
-- after Auth has revoked every other refresh-token session, preserving p_session_id.
create or replace function public.beekeep_internal_device_finalize_request(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_finalization_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
    created_device_id uuid;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
    for update;

    if request_row.id is null then
        raise exception 'This phone approval request could not be found.'
            using errcode = 'P0002';
    end if;

    if request_row.request_status = 'approved' then
        select d.id into created_device_id
        from beekeep_private.trusted_devices d
        where d.user_id = p_user_id
          and d.session_id = p_session_id
          and d.key_fingerprint = request_row.key_fingerprint
          and d.public_key = request_row.public_key
          and d.device_status = 'active'
          and d.revoked_at is null;

        if created_device_id is not null then
            return created_device_id;
        end if;
    end if;

    if request_row.request_status <> 'finalizing'
       or request_row.finalization_challenge_hash is distinct from p_finalization_challenge_hash then
        raise exception 'This phone approval is no longer ready to finalize. Request a new challenge.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The new phone sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    if exists (
        select 1 from beekeep_private.trusted_devices d
        where d.user_id = p_user_id
          and d.device_status = 'active'
          and d.revoked_at is null
    ) then
        raise exception 'Another phone is still active. The new phone was not activated.'
            using errcode = '42501';
    end if;

    insert into beekeep_private.trusted_devices (
        user_id, session_id, key_fingerprint, public_key, device_label,
        device_status, created_at, last_verified_at
    )
    values (
        request_row.user_id, request_row.session_id, request_row.key_fingerprint,
        request_row.public_key, request_row.device_label, 'active',
        pg_catalog.clock_timestamp(), pg_catalog.clock_timestamp()
    )
    returning id into created_device_id;

    update beekeep_private.device_access_requests r
    set request_status = 'approved',
        finalized_at = pg_catalog.clock_timestamp(),
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.id = request_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status in ('pending', 'approved_pending_finalize', 'finalizing');

    return created_device_id;
end;
$function$;

-- If Auth revocation fails, or the post-revocation database finalization needs a retry,
-- release the short finalizing lease without restoring the previous trusted phone.
create or replace function public.beekeep_internal_device_abort_finalization(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_finalization_challenge_hash text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'finalizing'
      and r.finalization_challenge_hash = p_finalization_challenge_hash;

    return found;
end;
$function$;

-- Internal operations are callable only by the trusted-device Edge Function's service key.
revoke all on function public.beekeep_internal_device_finalize_context(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_begin_finalization(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_finalize_request(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_abort_finalization(uuid, uuid, uuid, text)
    from public, anon, authenticated;

grant execute on function public.beekeep_internal_device_finalize_context(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_begin_finalization(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_finalize_request(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_abort_finalization(uuid, uuid, uuid, text)
    to service_role;

commit;

            and finalization_challenge_expires_at is not null
        )
    );

create index if not exists device_access_requests_finalization_user_idx
    on beekeep_private.device_access_requests (user_id, finalization_started_at desc)
    where request_status in ('approved_pending_finalize', 'finalizing');

-- The trusted phone approves the replacement, but does not activate the new phone.
-- The previous device is revoked immediately; cloud access remains closed until B finishes.
create or replace function public.beekeep_internal_device_approve_request(
    p_request_id uuid,
    p_user_id uuid,
    p_approver_session_id uuid,
    p_approval_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
    approver_row beekeep_private.trusted_devices%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.approval_challenge_hash = p_approval_challenge_hash
      and r.approval_challenge_expires_at > pg_catalog.statement_timestamp()
    for update;

    if request_row.id is null then
        raise exception 'This phone request has expired or is no longer pending.'
            using errcode = 'P0002';
    end if;

    select d.* into approver_row
    from beekeep_private.trusted_devices d
    join auth.sessions s on s.id = d.session_id and s.user_id = d.user_id
    where d.user_id = p_user_id
      and d.session_id = p_approver_session_id
      and d.device_status = 'active'
      and d.revoked_at is null
      and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    for update of d;

    if approver_row.id is null then
        raise exception 'The approving BeeKeep phone is no longer trusted.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = request_row.session_id
          and s.user_id = request_row.user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The new phone sign-in session has expired. Start again.'
            using errcode = '42501';
    end if;

    update beekeep_private.trusted_devices d
    set device_status = 'replaced',
        revoked_at = pg_catalog.clock_timestamp(),
        revoked_reason = 'Replaced after trusted-phone approval; new phone must finalize'
    where d.id = approver_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = approver_row.id,
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null,
        finalized_at = null
    where r.id = request_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status = 'pending';

    return approver_row.id;
end;
$function$;

-- Same physical installation, fresh Auth session. Revoke the old session binding first;
-- the replacement binding is created only by the common finalization operation.
create or replace function public.beekeep_internal_device_rebind_session(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
    device_row beekeep_private.trusted_devices%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'pending'
      and r.request_mode = 'same_device_rebind'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.challenge_hash = p_challenge_hash
    for update;

    if request_row.id is null then
        raise exception 'This phone registration is no longer available.'
            using errcode = 'P0002';
    end if;

    select d.* into device_row
    from beekeep_private.trusted_devices d
    where d.user_id = p_user_id
      and d.key_fingerprint = request_row.key_fingerprint
      and d.public_key = request_row.public_key
      and d.device_status = 'active'
      and d.revoked_at is null
    for update;

    if device_row.id is null then
        raise exception 'This key is not registered as the trusted BeeKeep phone.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    update beekeep_private.trusted_devices d
    set device_status = 'replaced',
        revoked_at = pg_catalog.clock_timestamp(),
        revoked_reason = 'Same-phone session rebind; new session must finalize'
    where d.id = device_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = device_row.id,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null,
        finalized_at = null
    where r.id = request_row.id;

    return device_row.id;
end;
$function$;

-- Email recovery and first-device setup enter the same pending-finalization state.
-- A recent verified email OTP authorizes this replacement, but never activates a device
-- without the later Keystore proof and Auth-session cutover.
create or replace function public.beekeep_internal_device_recover_request(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'pending'
      and r.request_mode in ('first_device_setup', 'replacement')
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.challenge_hash = p_challenge_hash
    for update;

    if request_row.id is null then
        raise exception 'This phone registration has expired or is no longer pending.'
            using errcode = 'P0002';
    end if;

    if exists (
        select 1
        from beekeep_private.device_access_requests r
        join auth.sessions s on s.id = r.session_id and s.user_id = r.user_id
        where r.user_id = p_user_id
          and r.id <> request_row.id
          and r.request_status = 'finalizing'
          and r.finalization_started_at > pg_catalog.statement_timestamp() - interval '90 seconds'
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'Another phone is finishing its approval. Wait briefly and try again.'
            using errcode = '55P03';
    end if;

    if request_row.request_mode = 'first_device_setup'
       and exists (
           select 1
           from beekeep_private.trusted_devices d
           where d.user_id = p_user_id
             and d.device_status = 'active'
             and d.revoked_at is null
       ) then
        raise exception 'This account already has a trusted phone. Start a replacement request instead.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    update beekeep_private.trusted_devices d
    set device_status = 'replaced',
        revoked_at = pg_catalog.clock_timestamp(),
        revoked_reason = 'Replaced through verified email recovery; new phone must finalize'
    where d.user_id = p_user_id
      and d.device_status = 'active'
      and d.revoked_at is null;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status in ('pending', 'approved_pending_finalize', 'finalizing');

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = 'email_recovery',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null,
        finalized_at = null
    where r.id = request_row.id;

    return null;
end;
$function$;

-- The new phone asks for a short-lived challenge only for its own approved request.
-- A stuck finalization lease can be reclaimed after 90 seconds; a live operation cannot
-- be overwritten by a second recovery attempt.
create or replace function public.beekeep_device_issue_finalize_challenge(
    p_request_id uuid
)
returns text
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    session_claim text := (select auth.jwt() ->> 'session_id');
    caller_session_id uuid;
    request_row beekeep_private.device_access_requests%rowtype;
    challenge_value text;
begin
    if caller_id is null or session_claim is null
       or session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        raise exception 'Sign in to finish approving this phone.'
            using errcode = '42501';
    end if;
    caller_session_id := session_claim::uuid;

    perform 1 from auth.users u where u.id = caller_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = caller_id
      and r.session_id = caller_session_id
    for update;

    if request_row.id is null then
        raise exception 'This phone approval request could not be found.'
            using errcode = 'P0002';
    end if;

    if request_row.request_status = 'finalizing' then
        if request_row.finalization_started_at >
            pg_catalog.statement_timestamp() - interval '90 seconds' then
            raise exception 'This phone approval is already being finalized. Try again shortly.'
                using errcode = '55P03';
        end if;

        update beekeep_private.device_access_requests r
        set request_status = 'approved_pending_finalize',
            finalization_started_at = null,
            finalization_challenge_hash = null,
            finalization_challenge_expires_at = null
        where r.id = request_row.id;

        request_row.request_status := 'approved_pending_finalize';
    end if;

    if request_row.request_status <> 'approved_pending_finalize'
       or request_row.challenge_verified_at is null then
        raise exception 'This phone is not waiting for final approval.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = caller_session_id
          and s.user_id = caller_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    challenge_value := pg_catalog.encode(extensions.gen_random_bytes(32), 'base64');

    update beekeep_private.device_access_requests r
    set finalization_challenge_hash = pg_catalog.encode(
            extensions.digest(pg_catalog.convert_to(challenge_value, 'UTF8'), 'sha256'),
            'hex'
        ),
        finalization_challenge_expires_at = pg_catalog.clock_timestamp() + interval '2 minutes'
    where r.id = request_row.id;

    return challenge_value;
end;
$function$;

revoke all on function public.beekeep_device_issue_finalize_challenge(uuid)
    from public, anon;
grant execute on function public.beekeep_device_issue_finalize_challenge(uuid)
    to authenticated;

-- Service-only context: the Edge Function verifies B's fresh Keystore signature before
-- beginning any Auth session revocation.
create or replace function public.beekeep_internal_device_finalize_context(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
)
returns table (
    public_key text,
    finalization_challenge_hash text,
    finalization_challenge_expires_at timestamptz,
    request_status text,
    request_mode text
)
language sql
stable
security definer
set search_path = ''
as $function$
    select r.public_key, r.finalization_challenge_hash,
           r.finalization_challenge_expires_at, r.request_status, r.request_mode
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id;
$function$;

-- Serialize cutover start with account-level recovery and other approval operations.
create or replace function public.beekeep_internal_device_begin_finalization(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_finalization_challenge_hash text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
    for update;

    if request_row.id is null then
        raise exception 'This phone approval request could not be found.'
            using errcode = 'P0002';
    end if;

    if request_row.request_status = 'approved'
       and exists (
           select 1 from beekeep_private.trusted_devices d
           where d.user_id = p_user_id
             and d.session_id = p_session_id
             and d.key_fingerprint = request_row.key_fingerprint
             and d.device_status = 'active'
             and d.revoked_at is null
       ) then
        return true;
    end if;

    if request_row.request_status = 'finalizing'
       and request_row.finalization_started_at >
           pg_catalog.statement_timestamp() - interval '90 seconds' then
        raise exception 'This phone approval is already being finalized. Try again shortly.'
            using errcode = '55P03';
    end if;

    if request_row.request_status = 'finalizing' then
        update beekeep_private.device_access_requests r
        set request_status = 'approved_pending_finalize',
            finalization_started_at = null,
            finalization_challenge_hash = null,
            finalization_challenge_expires_at = null
        where r.id = request_row.id;
        request_row.request_status := 'approved_pending_finalize';
    end if;

    if request_row.request_status <> 'approved_pending_finalize'
       or request_row.finalization_challenge_hash is distinct from p_finalization_challenge_hash
       or request_row.finalization_challenge_expires_at <= pg_catalog.statement_timestamp() then
        raise exception 'The final phone approval challenge is invalid or expired.'
            using errcode = '42501';
    end if;

    if request_row.challenge_verified_at is null or not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The new phone sign-in session has expired. Start again.'
            using errcode = '42501';
    end if;

    if exists (
        select 1 from beekeep_private.trusted_devices d
        where d.user_id = p_user_id
          and d.device_status = 'active'
          and d.revoked_at is null
    ) then
        raise exception 'Another phone is still active. Refresh the request and try again.'
            using errcode = '42501';
    end if;

    update beekeep_private.device_access_requests r
    set request_status = 'finalizing',
        finalization_started_at = pg_catalog.clock_timestamp()
    where r.id = request_row.id;

    return true;
end;
$function$;

-- Only this function creates the replacement's active trusted-device row. It is called
-- after Auth has revoked every other refresh-token session, preserving p_session_id.
create or replace function public.beekeep_internal_device_finalize_request(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_finalization_challenge_hash text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
    created_device_id uuid;
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
    for update;

    if request_row.id is null then
        raise exception 'This phone approval request could not be found.'
            using errcode = 'P0002';
    end if;

    if request_row.request_status = 'approved' then
        select d.id into created_device_id
        from beekeep_private.trusted_devices d
        where d.user_id = p_user_id
          and d.session_id = p_session_id
          and d.key_fingerprint = request_row.key_fingerprint
          and d.public_key = request_row.public_key
          and d.device_status = 'active'
          and d.revoked_at is null;

        if created_device_id is not null then
            return created_device_id;
        end if;
    end if;

    if request_row.request_status <> 'finalizing'
       or request_row.finalization_challenge_hash is distinct from p_finalization_challenge_hash then
        raise exception 'This phone approval is no longer ready to finalize. Request a new challenge.'
            using errcode = '42501';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The new phone sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    if exists (
        select 1 from beekeep_private.trusted_devices d
        where d.user_id = p_user_id
          and d.device_status = 'active'
          and d.revoked_at is null
    ) then
        raise exception 'Another phone is still active. The new phone was not activated.'
            using errcode = '42501';
    end if;

    insert into beekeep_private.trusted_devices (
        user_id, session_id, key_fingerprint, public_key, device_label,
        device_status, created_at, last_verified_at
    )
    values (
        request_row.user_id, request_row.session_id, request_row.key_fingerprint,
        request_row.public_key, request_row.device_label, 'active',
        pg_catalog.clock_timestamp(), pg_catalog.clock_timestamp()
    )
    returning id into created_device_id;

    update beekeep_private.device_access_requests r
    set request_status = 'approved',
        finalized_at = pg_catalog.clock_timestamp(),
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.id = request_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status in ('pending', 'approved_pending_finalize', 'finalizing');

    return created_device_id;
end;
$function$;

-- If Auth revocation fails, or the post-revocation database finalization needs a retry,
-- release the short finalizing lease without restoring the previous trusted phone.
create or replace function public.beekeep_internal_device_abort_finalization(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_finalization_challenge_hash text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
begin
    perform 1 from auth.users u where u.id = p_user_id for update;

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'finalizing'
      and r.finalization_challenge_hash = p_finalization_challenge_hash;

    return found;
end;
$function$;

-- Internal operations are callable only by the trusted-device Edge Function's service key.
revoke all on function public.beekeep_internal_device_finalize_context(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_begin_finalization(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_finalize_request(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_abort_finalization(uuid, uuid, uuid, text)
    from public, anon, authenticated;

grant execute on function public.beekeep_internal_device_finalize_context(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_begin_finalization(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_finalize_request(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_abort_finalization(uuid, uuid, uuid, text)
    to service_role;

commit;
