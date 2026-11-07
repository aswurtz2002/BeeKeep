-- Trusted-device request, proof, approval, denial, recovery and rebind API.
-- These functions do not gate ordinary BeeKeep data yet. The enforcement migration
-- is kept separate until the Android app can complete device authorization.
begin;

alter table beekeep_private.device_access_requests
    add column if not exists request_mode text not null default 'first_device_setup',
    add column if not exists challenge_hash text,
    add column if not exists challenge_verified_at timestamptz,
    add column if not exists approval_challenge_hash text,
    add column if not exists approval_challenge_expires_at timestamptz;

do $constraints$
begin
    if not exists (
        select 1 from pg_constraint
        where conrelid = 'beekeep_private.device_access_requests'::regclass
          and conname = 'device_access_requests_request_mode_check'
    ) then
        alter table beekeep_private.device_access_requests
            add constraint device_access_requests_request_mode_check
            check (request_mode in ('first_device_setup', 'replacement', 'same_device_rebind'));
    end if;
end;
$constraints$;

create index if not exists device_access_requests_verified_pending_idx
    on beekeep_private.device_access_requests (user_id, created_at desc)
    where request_status = 'pending' and challenge_verified_at is not null;

-- Initial enrollment/request creation is intentionally callable by an authenticated,
-- not-yet-trusted session. It reveals only a short-lived challenge for that same session.
create or replace function public.beekeep_device_begin_request(
    p_public_key text,
    p_device_label text
)
returns table (
    request_id uuid,
    request_mode text,
    challenge text,
    expires_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    session_claim text := (select auth.jwt() ->> 'session_id');
    caller_session_id uuid;
    key_bytes bytea;
    fingerprint text;
    cleaned_label text := pg_catalog.left(pg_catalog.btrim(coalesce(p_device_label, '')), 80);
    selected_mode text;
    challenge_value text;
    request_expires_at timestamptz := pg_catalog.clock_timestamp() + interval '5 minutes';
    request_id_value uuid;
    recent_request_count integer;
begin
    if caller_id is null then
        raise exception 'Sign in to BeeKeep before registering this phone.'
            using errcode = '42501';
    end if;

    if session_claim is null
       or session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        raise exception 'This BeeKeep sign-in session could not be verified.'
            using errcode = '42501';
    end if;
    caller_session_id := session_claim::uuid;

    if cleaned_label = '' then
        raise exception 'Enter a device label.'
            using errcode = '22023';
    end if;

    if p_public_key is null
       or pg_catalog.length(p_public_key) not between 80 and 4096
       or p_public_key !~ '^[A-Za-z0-9+/]+={0,2}$' then
        raise exception 'The phone registration key is invalid.'
            using errcode = '22023';
    end if;

    begin
        key_bytes := pg_catalog.decode(p_public_key, 'base64');
    exception when others then
        raise exception 'The phone registration key is invalid.'
            using errcode = '22023';
    end;
    if pg_catalog.length(key_bytes) < 64 or pg_catalog.length(key_bytes) > 3072 then
        raise exception 'The phone registration key has an unsupported size.'
            using errcode = '22023';
    end if;

    fingerprint := pg_catalog.encode(extensions.digest(key_bytes, 'sha256'), 'hex');

    if not exists (
        select 1
        from auth.sessions s
        where s.id = caller_session_id
          and s.user_id = caller_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    select count(*)::integer
      into recent_request_count
      from beekeep_private.device_access_requests r
     where r.user_id = caller_id
       and r.created_at > pg_catalog.clock_timestamp() - interval '15 minutes'
       and r.session_id <> caller_session_id;

    if recent_request_count >= 5 then
        raise exception 'Too many phone approval requests. Wait 15 minutes and try again.'
            using errcode = '53300';
    end if;

    if exists (
        select 1
        from beekeep_private.trusted_devices d
        where d.user_id = caller_id
          and d.key_fingerprint = fingerprint
          and d.device_status = 'active'
          and d.revoked_at is null
    ) then
        selected_mode := 'same_device_rebind';
    elsif exists (
        select 1
        from beekeep_private.trusted_devices d
        where d.user_id = caller_id
          and d.device_status = 'active'
          and d.revoked_at is null
    ) then
        selected_mode := 'replacement';
    else
        selected_mode := 'first_device_setup';
    end if;

    challenge_value := pg_catalog.encode(extensions.gen_random_bytes(32), 'base64');

    insert into beekeep_private.device_access_requests as current_request (
        user_id,
        session_id,
        key_fingerprint,
        public_key,
        device_label,
        request_status,
        approval_method,
        created_at,
        expires_at,
        decided_at,
        decided_by_device_id,
        request_mode,
        challenge_hash,
        challenge_verified_at,
        approval_challenge_hash,
        approval_challenge_expires_at
    )
    values (
        caller_id,
        caller_session_id,
        fingerprint,
        p_public_key,
        cleaned_label,
        'pending',
        null,
        pg_catalog.clock_timestamp(),
        request_expires_at,
        null,
        null,
        selected_mode,
        pg_catalog.encode(extensions.digest(pg_catalog.convert_to(challenge_value, 'UTF8'), 'sha256'), 'hex'),
        null,
        null,
        null
    )
    on conflict (session_id) do update
        set user_id = excluded.user_id,
            key_fingerprint = excluded.key_fingerprint,
            public_key = excluded.public_key,
            device_label = excluded.device_label,
            request_status = 'pending',
            approval_method = null,
            created_at = excluded.created_at,
            expires_at = excluded.expires_at,
            decided_at = null,
            decided_by_device_id = null,
            request_mode = excluded.request_mode,
            challenge_hash = excluded.challenge_hash,
            challenge_verified_at = null,
            approval_challenge_hash = null,
            approval_challenge_expires_at = null
    returning current_request.id into request_id_value;

    return query
    select request_id_value, selected_mode, challenge_value, request_expires_at;
end;
$function$;

revoke all on function public.beekeep_device_begin_request(text, text)
    from public, anon;
grant execute on function public.beekeep_device_begin_request(text, text)
    to authenticated;

-- A pending session can query only its own registration state.
create or replace function public.beekeep_device_get_request_status()
returns table (
    request_id uuid,
    request_status text,
    request_mode text,
    expires_at timestamptz,
    challenge_verified_at timestamptz,
    approval_method text
)
language sql
stable
security definer
set search_path = ''
as $function$
    select
        r.id,
        r.request_status,
        r.request_mode,
        r.expires_at,
        r.challenge_verified_at,
        r.approval_method
    from beekeep_private.device_access_requests r
    where r.user_id = (select auth.uid())
      and r.session_id = nullif((select auth.jwt() ->> 'session_id'), '')::uuid
    limit 1;
$function$;

revoke all on function public.beekeep_device_get_request_status()
    from public, anon;
grant execute on function public.beekeep_device_get_request_status()
    to authenticated;

-- The old phone can see only verified, unexpired requests for its own account.
create or replace function public.beekeep_device_list_pending_requests()
returns table (
    request_id uuid,
    device_label text,
    request_mode text,
    created_at timestamptz,
    expires_at timestamptz
)
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
begin
    if caller_id is null or not public.beekeep_device_session_is_trusted() then
        raise exception 'Only the trusted BeeKeep phone can review new-device requests.'
            using errcode = '42501',
                  detail = 'BEEKEEP_DEVICE_NOT_APPROVED';
    end if;

    return query
    select r.id, r.device_label, r.request_mode, r.created_at, r.expires_at
    from beekeep_private.device_access_requests r
    where r.user_id = caller_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
    order by r.created_at desc;
end;
$function$;

revoke all on function public.beekeep_device_list_pending_requests()
    from public, anon;
grant execute on function public.beekeep_device_list_pending_requests()
    to authenticated;

-- Generate a distinct short-lived approval challenge only after the trusted phone
-- opens a verified replacement request.
create or replace function public.beekeep_device_issue_approval_challenge(
    p_request_id uuid
)
returns text
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    challenge_value text;
begin
    if caller_id is null or not public.beekeep_device_session_is_trusted() then
        raise exception 'Only the trusted BeeKeep phone can approve a replacement.'
            using errcode = '42501',
                  detail = 'BEEKEEP_DEVICE_NOT_APPROVED';
    end if;

    perform 1
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = caller_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
    for update;

    if not found then
        raise exception 'This phone request is no longer available.'
            using errcode = 'P0002';
    end if;

    challenge_value := pg_catalog.encode(extensions.gen_random_bytes(32), 'base64');

    update beekeep_private.device_access_requests r
    set approval_challenge_hash = pg_catalog.encode(
            extensions.digest(pg_catalog.convert_to(challenge_value, 'UTF8'), 'sha256'),
            'hex'
        ),
        approval_challenge_expires_at = pg_catalog.clock_timestamp() + interval '2 minutes'
    where r.id = p_request_id;

    return challenge_value;
end;
$function$;

revoke all on function public.beekeep_device_issue_approval_challenge(uuid)
    from public, anon;
grant execute on function public.beekeep_device_issue_approval_challenge(uuid)
    to authenticated;

-- Service-only lookup: the Edge Function verifies the new phone's ECDSA signature.
create or replace function public.beekeep_internal_device_request_verification_context(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
)
returns table (
    user_id uuid,
    session_id uuid,
    public_key text,
    challenge_hash text,
    expires_at timestamptz,
    request_mode text,
    request_status text
)
language sql
stable
security definer
set search_path = ''
as $function$
    select r.user_id, r.session_id, r.public_key, r.challenge_hash,
           r.expires_at, r.request_mode, r.request_status
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id;
$function$;

-- Only the service-side verifier may mark proof-of-possession complete.
create or replace function public.beekeep_internal_device_mark_request_verified(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid,
    p_challenge_hash text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
begin
    update beekeep_private.device_access_requests r
    set challenge_verified_at = pg_catalog.clock_timestamp()
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'pending'
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.challenge_hash = p_challenge_hash;

    return found;
end;
$function$;

-- Service-only approval context: it binds the approval challenge to the caller's
-- currently active device key and the specific pending request.
create or replace function public.beekeep_internal_device_approval_context(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
)
returns table (
    trusted_public_key text,
    approval_challenge_hash text,
    approval_challenge_expires_at timestamptz
)
language sql
stable
security definer
set search_path = ''
as $function$
    select d.public_key, r.approval_challenge_hash, r.approval_challenge_expires_at
    from beekeep_private.device_access_requests r
    join beekeep_private.trusted_devices d
      on d.user_id = r.user_id
     and d.session_id = p_session_id
     and d.device_status = 'active'
     and d.revoked_at is null
    join auth.sessions s
      on s.id = d.session_id
     and s.user_id = d.user_id
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.approval_challenge_hash is not null
      and r.approval_challenge_expires_at > pg_catalog.statement_timestamp()
      and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp());
$function$;

-- Atomic replacement. The Edge Function calls this only after verifying the old
-- phone's signature over the one-time approval challenge.
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
    created_device_id uuid;
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
        revoked_reason = 'Replaced after trusted-phone approval'
    where d.id = approver_row.id;

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
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = approver_row.id,
        approval_challenge_hash = null,
        approval_challenge_expires_at = null
    where r.id = request_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status = 'pending';

    return created_device_id;
end;
$function$;

create or replace function public.beekeep_internal_device_deny_request(
    p_request_id uuid,
    p_user_id uuid,
    p_approver_session_id uuid,
    p_approval_challenge_hash text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    approver_device_id uuid;
begin
    select d.id into approver_device_id
    from beekeep_private.trusted_devices d
    join auth.sessions s on s.id = d.session_id and s.user_id = d.user_id
    where d.user_id = p_user_id
      and d.session_id = p_approver_session_id
      and d.device_status = 'active'
      and d.revoked_at is null
      and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    for update of d;

    if approver_device_id is null then
        raise exception 'The approving BeeKeep phone is no longer trusted.'
            using errcode = '42501';
    end if;

    update beekeep_private.device_access_requests r
    set request_status = 'denied',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = approver_device_id,
        approval_challenge_hash = null,
        approval_challenge_expires_at = null
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
      and r.approval_challenge_hash = p_approval_challenge_hash
      and r.approval_challenge_expires_at > pg_catalog.statement_timestamp();

    return found;
end;
$function$;

-- Same installation, new Supabase session: possession of the original Keystore
-- key rebinds the active installation without requiring another phone to approve.
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
    set session_id = p_session_id,
        last_verified_at = pg_catalog.clock_timestamp()
    where d.id = device_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'approved',
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = device_row.id
    where r.id = request_row.id;

    return device_row.id;
end;
$function$;

-- First-time enrollment and lost-phone recovery. The Edge Function requires a
-- recent Supabase email-OTP AMR before calling this service-only operation.
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
    created_device_id uuid;
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
        revoked_reason = 'Replaced through verified email recovery'
    where d.user_id = p_user_id
      and d.device_status = 'active'
      and d.revoked_at is null;

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
        approval_method = 'email_recovery',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = null
    where r.id = request_row.id;

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = pg_catalog.clock_timestamp(),
        approval_challenge_hash = null,
        approval_challenge_expires_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status = 'pending';

    return created_device_id;
end;
$function$;

-- The internal operations are reachable only from the Edge Function's server key.
revoke all on function public.beekeep_internal_device_request_verification_context(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_mark_request_verified(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_approval_context(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_approve_request(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_deny_request(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_rebind_session(uuid, uuid, uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_recover_request(uuid, uuid, uuid, text)
    from public, anon, authenticated;

grant execute on function public.beekeep_internal_device_request_verification_context(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_mark_request_verified(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_approval_context(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_approve_request(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_deny_request(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_rebind_session(uuid, uuid, uuid, text)
    to service_role;
grant execute on function public.beekeep_internal_device_recover_request(uuid, uuid, uuid, text)
    to service_role;

commit;
