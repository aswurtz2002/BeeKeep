-- Replace the phone-to-phone approval with email-code replacement.
-- The initial request is created only from a password/Google-authenticated session.
-- The email OTP is then verified by Supabase Auth and the request is rebound to that
-- fresh OTP session before the service revokes all other Auth sessions.
begin;

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
        raise exception 'Sign in to BeeKeep before verifying this phone.'
            using errcode = '42501';
    end if;

    if session_claim is null
       or session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        raise exception 'This BeeKeep sign-in session could not be verified.'
            using errcode = '42501';
    end if;
    caller_session_id := session_claim::uuid;

    -- A code by itself is not enough to start a replacement. The session which
    -- requests the code must already have been established by password or OAuth.
    if not exists (
        select 1
        from pg_catalog.jsonb_array_elements(
            case
                when pg_catalog.jsonb_typeof((select auth.jwt() -> 'amr')) = 'array'
                    then (select auth.jwt() -> 'amr')
                else '[]'::jsonb
            end
        ) as amr(value)
        where amr.value ->> 'method' in ('password', 'oauth', 'oauth_provider/authorization_code', 'sso/saml')
    ) then
        raise exception 'Sign in with your password or Google before requesting a phone security code.'
            using errcode = '42501';
    end if;

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

    select r.* into existing_request
    from beekeep_private.device_access_requests r
    where r.session_id = caller_session_id
      and r.user_id = caller_id
    for update;

    if existing_request.id is not null
       and existing_request.request_status = 'pending'
       and existing_request.expires_at > pg_catalog.statement_timestamp() then
        -- A resend reuses the same session-bound request but refreshes its five-minute
        -- server window so the new email code has a full verification period.
        update beekeep_private.device_access_requests r
        set device_label = cleaned_label,
            created_at = pg_catalog.clock_timestamp(),
            expires_at = request_expires_at,
            challenge_verified_at = pg_catalog.clock_timestamp()
        where r.id = existing_request.id
        returning r.* into existing_request;

        return query
        select existing_request.id, existing_request.request_mode,
               existing_request.request_status, existing_request.expires_at;
        return;
    end if;

    if existing_request.id is not null
       and existing_request.request_status in ('approved_pending_finalize', 'finalizing') then
        return query
        select existing_request.id, existing_request.request_mode,
               existing_request.request_status, existing_request.expires_at;
        return;
    end if;

    select count(*)::integer into recent_request_count
    from beekeep_private.device_access_requests r
    where r.user_id = caller_id
      and r.created_at > pg_catalog.clock_timestamp() - interval '15 minutes';

    if recent_request_count >= 5 then
        raise exception 'Too many phone verification requests. Wait 15 minutes and try again.'
            using errcode = '53300';
    end if;

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

-- Bind a previously password/Google-authenticated, five-minute request to the
-- fresh Auth session created only after the user verifies the emailed OTP.
create or replace function public.beekeep_internal_device_simple_bind_email_session(
    p_request_id uuid,
    p_user_id uuid,
    p_verified_session_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    request_row beekeep_private.device_access_requests%rowtype;
begin
    if p_request_id is null or p_user_id is null or p_verified_session_id is null then
        raise exception 'The phone verification request is invalid.'
            using errcode = '22023';
    end if;

    perform 1 from auth.users u where u.id = p_user_id for update;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_verified_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The verified email session is no longer valid. Request a new code.'
            using errcode = '42501';
    end if;

    select r.* into request_row
    from beekeep_private.device_access_requests r
    where r.id = p_request_id
      and r.user_id = p_user_id
    for update;

    if request_row.id is null then
        raise exception 'This phone verification request could not be found. Request a new code.'
            using errcode = 'P0002';
    end if;

    -- Safe retry after the request was already rebound and email recovery began.
    if request_row.session_id = p_verified_session_id
       and request_row.request_status in ('pending', 'approved_pending_finalize', 'finalizing', 'approved') then
        return true;
    end if;

    if request_row.request_status <> 'pending'
       or request_row.request_mode not in ('first_device_setup', 'replacement')
       or request_row.challenge_verified_at is null
       or request_row.expires_at <= pg_catalog.statement_timestamp() then
        raise exception 'This phone verification code has expired. Request a new code.'
            using errcode = 'P0002';
    end if;

    update beekeep_private.device_access_requests r
    set session_id = p_verified_session_id
    where r.id = p_request_id
      and r.user_id = p_user_id
      and r.request_status = 'pending';

    return found;
end;
$function$;

revoke all on function public.beekeep_internal_device_simple_bind_email_session(uuid, uuid, uuid)
    from public, anon, authenticated;
grant execute on function public.beekeep_internal_device_simple_bind_email_session(uuid, uuid, uuid)
    to service_role;

-- Make the email-verified recovery step safe to retry after the OTP session is rebound.
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
    for update;

    if request_row.id is null then
        raise exception 'This phone recovery request could not be found.'
            using errcode = 'P0002';
    end if;

    -- A lost Edge Function response can safely retry recovery/finalization.
    if request_row.request_status in ('approved_pending_finalize', 'finalizing', 'approved')
       and request_row.request_mode in ('first_device_setup', 'replacement') then
        return true;
    end if;

    if request_row.request_status <> 'pending'
       or request_row.request_mode not in ('first_device_setup', 'replacement')
       or request_row.challenge_verified_at is null
       or request_row.expires_at <= pg_catalog.statement_timestamp() then
        raise exception 'This phone verification request has expired. Request a new code.'
            using errcode = 'P0002';
    end if;

    if not exists (
        select 1 from auth.sessions s
        where s.id = p_session_id
          and s.user_id = p_user_id
          and (s.not_after is null or s.not_after > pg_catalog.statement_timestamp())
    ) then
        raise exception 'The verified email session has expired. Request a new code.'
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

revoke all on function public.beekeep_internal_device_simple_recover_request(uuid, uuid, uuid)
    from public, anon, authenticated;
grant execute on function public.beekeep_internal_device_simple_recover_request(uuid, uuid, uuid)
    to service_role;

-- The app no longer accepts phone-to-phone approval decisions.
revoke all on function public.beekeep_device_simple_approve_request(uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_device_simple_deny_request(uuid)
    from public, anon, authenticated;

comment on function public.beekeep_device_simple_begin_request(text) is
    'Starts a short-lived device replacement only from a password/Google-authenticated session; email OTP is required before the new device is activated.';
comment on function public.beekeep_internal_device_simple_bind_email_session(uuid, uuid, uuid) is
    'Binds a five-minute password/Google-authenticated replacement request to the fresh Supabase Auth session created by the verified email OTP.';

commit;
