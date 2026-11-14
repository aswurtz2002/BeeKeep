-- Durable per-session device-revocation events for the old phone.
-- Realtime RLS exposes an event only to the exact Supabase session being revoked.
begin;

create table if not exists public.beekeep_device_session_events (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    revoked_session_id uuid not null,
    event_type text not null check (event_type in ('session_replaced')),
    created_at timestamptz not null default pg_catalog.clock_timestamp(),
    unique (revoked_session_id, event_type)
);

alter table public.beekeep_device_session_events enable row level security;

revoke all on table public.beekeep_device_session_events from anon, authenticated;
grant select on table public.beekeep_device_session_events to authenticated;

drop policy if exists beekeep_device_session_events_read_own_revocation
    on public.beekeep_device_session_events;
create policy beekeep_device_session_events_read_own_revocation
    on public.beekeep_device_session_events
    for select
    to authenticated
    using (
        user_id = (select auth.uid())
        and revoked_session_id = nullif((select auth.jwt() ->> 'session_id'), '')::uuid
    );

do $publication$
begin
    if not exists (
        select 1 from pg_publication where pubname = 'supabase_realtime'
    ) then
        execute 'create publication supabase_realtime';
    end if;

    if not exists (
        select 1
        from pg_publication_tables
        where pubname = 'supabase_realtime'
          and schemaname = 'public'
          and tablename = 'beekeep_device_session_events'
    ) then
        execute 'alter publication supabase_realtime add table public.beekeep_device_session_events';
    end if;
end;
$publication$;

-- Emit the event before marking the former trusted-device row as replaced.
-- Auth refresh-session revocation happens in the Edge Function; this event handles the
-- still-issued access token and wakes the old app immediately when its Realtime socket survives.
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

    insert into public.beekeep_device_session_events (
        user_id, revoked_session_id, event_type
    )
    select d.user_id, d.session_id, 'session_replaced'
    from beekeep_private.trusted_devices d
    where d.user_id = p_user_id
      and d.session_id <> p_session_id
      and d.device_status = 'active'
      and d.revoked_at is null
    on conflict (revoked_session_id, event_type) do nothing;

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

commit;
