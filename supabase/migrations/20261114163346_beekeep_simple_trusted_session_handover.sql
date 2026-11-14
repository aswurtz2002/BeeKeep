-- Simplified one-session-per-account device handover.
-- Device identity is the authenticated Supabase session_id; no client-generated key,
-- registration signature, approval challenge, or second finalization challenge is needed.
-- The existing RLS/RPC guards continue to authorize only the one active session.
begin;

alter table beekeep_private.trusted_devices
    alter column key_fingerprint drop not null,
    alter column public_key drop not null;

alter table beekeep_private.device_access_requests
    alter column key_fingerprint drop not null,
    alter column public_key drop not null;

-- Create or reuse one short-lived request for the current authenticated session.
create or replace function public.beekeep_device_simple_begin_request(
    p_device_label text
)
returns table (
    request_id uuid,
    request_mode text,
    request_status text,
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
    active_device_id uuid;
    existing_request beekeep_private.device_access_requests%rowtype;
    selected_mode text;
    cleaned_label text := pg_catalog.left(pg_catalog.btrim(coalesce(p_device_label, '')), 80);
    request_expires_at timestamptz := pg_catalog.clock_timestamp() + interval '5 minutes';
    request_id_value uuid;
    recent_request_count integer;
begin
    if caller_id is null then
        raise exception 'Sign in to BeeKeep before requesting phone approval.'
            using errcode = '42501';
    end if;

    if session_claim is null
       or session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        raise exception 'This BeeKeep sign-in session could not be verified.'
            using errcode = '42501';
    end if;
    caller_session_id := session_claim::uuid;

    if cleaned_label = '' then
        cleaned_label := 'Android phone';
    end if;

    perform 1 from auth.users u where u.id = caller_id for update;

    if not exists (
        select 1 from auth.sessions s
        where s.id = caller_session_id
          and s.user_id = caller_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'This BeeKeep sign-in session has expired. Sign in again.'
            using errcode = '42501';
    end if;

    if public.beekeep_device_session_is_trusted() then
        raise exception 'This phone already has trusted BeeKeep access.'
            using errcode = '42501';
    end if;

    select r.* into existing_request
    from beekeep_private.device_access_requests r
    where r.session_id = caller_session_id
      and r.user_id = caller_id
    for update;

    if existing_request.id is not null
       and existing_request.request_status in ('pending', 'approved_pending_finalize', 'finalizing')
       and (
           existing_request.request_status <> 'pending'
           or existing_request.expires_at > pg_catalog.statement_timestamp()
       ) then
        return query
        select existing_request.id, existing_request.request_mode,
               existing_request.request_status, existing_request.expires_at;
        return;
    end if;

    select count(*)::integer into recent_request_count
    from beekeep_private.device_access_requests r
    where r.user_id = caller_id
      and r.created_at > pg_catalog.clock_timestamp() - interval '15 minutes'
      and r.session_id <> caller_session_id;

    if recent_request_count >= 5 then
        raise exception 'Too many phone approval requests. Wait 15 minutes and try again.'
            using errcode = '53300';
    end if;

    select d.id into active_device_id
    from beekeep_private.trusted_devices d
    join auth.sessions s on s.id = d.session_id and s.user_id = d.user_id
    where d.user_id = caller_id
      and d.device_status = 'active'
      and d.revoked_at is null
      and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    order by d.created_at desc
    limit 1
    for update of d;

    selected_mode := case when active_device_id is null
        then 'first_device_setup'
        else 'replacement'
    end;

    if existing_request.id is null then
        insert into beekeep_private.device_access_requests (
            user_id, session_id, key_fingerprint, public_key, device_label,
            request_status, approval_method, created_at, expires_at, decided_at,
            decided_by_device_id, request_mode, challenge_hash, challenge_verified_at,
            approval_challenge_hash, approval_challenge_expires_at,
            finalization_challenge_hash, finalization_challenge_expires_at,
            finalization_started_at, finalized_at
        )
        values (
            caller_id, caller_session_id, null, null, cleaned_label,
            'pending', null, pg_catalog.clock_timestamp(), request_expires_at, null,
            null, selected_mode, null, pg_catalog.clock_timestamp(),
            null, null, null, null, null, null
        )
        returning id into request_id_value;
    else
        update beekeep_private.device_access_requests r
        set key_fingerprint = null,
            public_key = null,
            device_label = cleaned_label,
            request_status = 'pending',
            approval_method = null,
            created_at = pg_catalog.clock_timestamp(),
            expires_at = request_expires_at,
            decided_at = null,
            decided_by_device_id = null,
            request_mode = selected_mode,
            challenge_hash = null,
            challenge_verified_at = pg_catalog.clock_timestamp(),
            approval_challenge_hash = null,
            approval_challenge_expires_at = null,
            finalization_challenge_hash = null,
            finalization_challenge_expires_at = null,
            finalization_started_at = null,
            finalized_at = null
        where r.id = existing_request.id
        returning r.id into request_id_value;
    end if;

    return query
    select request_id_value, selected_mode, 'pending'::text, request_expires_at;
end;
$function$;

revoke all on function public.beekeep_device_simple_begin_request(text)
    from public, anon;
grant execute on function public.beekeep_device_simple_begin_request(text)
    to authenticated;

-- Approval is authorized by the caller's currently trusted Supabase session.
-- The replacement does not become active until that replacement session finalizes.
create or replace function public.beekeep_device_simple_approve_request(
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
    approver_device_id uuid;
begin
    if caller_id is null or session_claim is null
       or session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
       or not public.beekeep_device_session_is_trusted() then
        raise exception 'Only the currently trusted BeeKeep phone can approve a replacement.'
            using errcode = '42501';
    end if;
    caller_session_id := session_claim::uuid;

    perform 1 from auth.users u where u.id = caller_id for update;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = caller_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
    for update;

    if request_row.id is null then
        raise exception 'This phone request has expired or is no longer waiting for approval.'
            using errcode = 'P0002';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = request_row.session_id
          and s.user_id = caller_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The new phone sign-in session has expired. Start again.'
            using errcode = '42501';
    end if;

    select d.id into approver_device_id
    from beekeep_private.trusted_devices d
    join auth.sessions s on s.id = d.session_id and s.user_id = d.user_id
    where d.user_id = caller_id
      and d.session_id = caller_session_id
      and d.device_status = 'active'
      and d.revoked_at is null
      and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    for update of d;

    if approver_device_id is null then
        raise exception 'The approving BeeKeep phone is no longer trusted.'
            using errcode = '42501';
    end if;

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = approver_device_id
    where r.id = request_row.id;

    return 'approved_pending_finalize';
end;
$function$;

revoke all on function public.beekeep_device_simple_approve_request(uuid)
    from public, anon;
grant execute on function public.beekeep_device_simple_approve_request(uuid)
    to authenticated;

create or replace function public.beekeep_device_simple_deny_request(
    p_request_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    request_row beekeep_private.device_access_requests%rowtype;
    approver_device_id uuid;
begin
    if caller_id is null or not public.beekeep_device_session_is_trusted() then
        raise exception 'Only the currently trusted BeeKeep phone can deny a request.'
            using errcode = '42501';
    end if;

    perform 1 from auth.users u where u.id = caller_id for update;

    select d.id into approver_device_id
    from beekeep_private.trusted_devices d
    join auth.sessions s on s.id = d.session_id and s.user_id = d.user_id
    where d.user_id = caller_id
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
        approval_method = 'trusted_device',
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = approver_device_id,
        approval_challenge_hash = null,
        approval_challenge_expires_at = null
    where r.id = p_request_id
      and r.user_id = caller_id
      and r.request_status = 'pending'
      and r.request_mode = 'replacement'
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp();

    return found;
end;
$function$;

revoke all on function public.beekeep_device_simple_deny_request(uuid)
    from public, anon;
grant execute on function public.beekeep_device_simple_deny_request(uuid)
    to authenticated;

-- Internal Edge Function operations: request IDs are always bound to the JWT session.
create or replace function public.beekeep_internal_device_simple_finalize_context(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
)
returns table (
    request_status text,
    request_mode text
)
language sql
stable
security definer
set search_path = ''
as $function$
    select r.request_status, r.request_mode
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id;
$function$;

create or replace function public.beekeep_internal_device_simple_begin_finalization(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
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
             and d.device_status = 'active'
             and d.revoked_at is null
       ) then
        return true;
    end if;

    if request_row.request_status = 'finalizing' then
        if request_row.finalization_started_at >
            pg_catalog.statement_timestamp() - interval '90 seconds' then
            raise exception 'This phone approval is already being completed. Try again shortly.'
                using errcode = '55P03';
        end if;

        update beekeep_private.device_access_requests r
        set request_status = 'approved_pending_finalize',
            finalization_started_at = null
        where r.id = request_row.id;
        request_row.request_status := 'approved_pending_finalize';
    end if;

    if request_row.request_status <> 'approved_pending_finalize'
       or request_row.challenge_verified_at is null then
        raise exception 'This phone has not been approved yet.'
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

    update beekeep_private.device_access_requests r
    set request_status = 'finalizing',
        finalization_started_at = pg_catalog.clock_timestamp()
    where r.id = request_row.id;

    return true;
end;
$function$;

create or replace function public.beekeep_internal_device_simple_abort_finalization(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
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
        finalization_started_at = null
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.session_id = p_session_id
      and r.request_status = 'finalizing';

    return found;
end;
$function$;

create or replace function public.beekeep_internal_device_simple_finalize_request(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
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
          and d.device_status = 'active'
          and d.revoked_at is null;

        if created_device_id is not null then
            return created_device_id;
        end if;
    end if;

    if request_row.request_status <> 'finalizing' then
        raise exception 'This phone approval is no longer ready to complete.'
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

    update beekeep_private.trusted_devices d
    set device_status = 'replaced',
        revoked_at = pg_catalog.clock_timestamp(),
        revoked_reason = 'Replaced by an approved new BeeKeep session'
    where d.user_id = p_user_id
      and d.device_status = 'active'
      and d.revoked_at is null;

    insert into beekeep_private.trusted_devices (
        user_id, session_id, key_fingerprint, public_key, device_label,
        device_status, created_at, last_verified_at
    )
    values (
        p_user_id, p_session_id, null, null, request_row.device_label,
        'active', pg_catalog.clock_timestamp(), pg_catalog.clock_timestamp()
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
        decided_at = coalesce(r.decided_at, pg_catalog.clock_timestamp()),
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status in ('pending', 'approved_pending_finalize', 'finalizing');

    return created_device_id;
end;
$function$;

-- Called only after the Edge Function verified a fresh email OTP in the current session.
create or replace function public.beekeep_internal_device_simple_recover_request(
    p_request_id uuid,
    p_user_id uuid,
    p_session_id uuid
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
      and r.request_status = 'pending'
      and r.request_mode in ('first_device_setup', 'replacement')
      and r.challenge_verified_at is not null
      and r.expires_at > pg_catalog.statement_timestamp()
    for update;

    if request_row.id is null then
        raise exception 'This phone recovery request has expired or is no longer pending.'
            using errcode = 'P0002';
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

    update beekeep_private.device_access_requests r
    set request_status = 'replaced',
        decided_at = coalesce(r.decided_at, pg_catalog.clock_timestamp()),
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.user_id = p_user_id
      and r.id <> request_row.id
      and r.request_status in ('pending', 'approved_pending_finalize', 'finalizing');

    update beekeep_private.device_access_requests r
    set request_status = 'approved_pending_finalize',
        approval_method = case
            when request_row.request_mode = 'first_device_setup' then 'first_device_setup'
            else 'email_recovery'
        end,
        decided_at = pg_catalog.clock_timestamp(),
        decided_by_device_id = null,
        finalization_challenge_hash = null,
        finalization_challenge_expires_at = null,
        finalization_started_at = null
    where r.id = request_row.id;

    return true;
end;
$function$;

revoke all on function public.beekeep_internal_device_simple_finalize_context(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_simple_begin_finalization(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_simple_abort_finalization(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_simple_finalize_request(uuid, uuid, uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_internal_device_simple_recover_request(uuid, uuid, uuid)
    from public, anon, authenticated;

grant execute on function public.beekeep_internal_device_simple_finalize_context(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_simple_begin_finalization(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_simple_abort_finalization(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_simple_finalize_request(uuid, uuid, uuid)
    to service_role;
grant execute on function public.beekeep_internal_device_simple_recover_request(uuid, uuid, uuid)
    to service_role;

comment on function public.beekeep_device_simple_begin_request(text) is
    'Creates one short-lived request bound to the caller Supabase session_id; the server, not the client, identifies the session.';
comment on function public.beekeep_device_simple_approve_request(uuid) is
    'Allows only the currently active trusted session to approve a replacement session.';
comment on function public.beekeep_internal_device_simple_finalize_request(uuid, uuid, uuid) is
    'Activates an approved replacement session after the Edge Function revokes every other Supabase Auth session.';

commit;
