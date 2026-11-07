-- Trusted-device registry and server-side session authorization primitive.
-- This foundation is intentionally non-enforcing: existing app clients remain online
-- until the Android enrollment flow and the complete RLS/RPC gate are deployed together.
begin;

create schema if not exists beekeep_private authorization postgres;
revoke all on schema beekeep_private from public, anon, authenticated, service_role;

create table if not exists beekeep_private.trusted_devices (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    session_id uuid not null,
    key_fingerprint text not null
        check (key_fingerprint ~ '^[0-9a-f]{64}$'),
    public_key text not null
        check (length(public_key) between 80 and 4096),
    device_label text not null
        check (length(btrim(device_label)) between 1 and 80),
    device_status text not null default 'active'
        check (device_status in ('active', 'revoked', 'replaced')),
    created_at timestamptz not null default clock_timestamp(),
    last_verified_at timestamptz not null default clock_timestamp(),
    revoked_at timestamptz,
    revoked_reason text,
    constraint trusted_devices_session_id_key unique (session_id),
    constraint trusted_devices_user_key_fingerprint_key unique (user_id, key_fingerprint),
    constraint trusted_devices_revocation_state_check check (
        (device_status = 'active' and revoked_at is null)
        or (device_status in ('revoked', 'replaced') and revoked_at is not null)
    )
);

-- A single active installation is allowed for each BeeKeep account. Historical
-- rows are retained for audit and are not eligible for access.
create unique index if not exists trusted_devices_one_active_per_user_idx
    on beekeep_private.trusted_devices (user_id)
    where device_status = 'active' and revoked_at is null;

create index if not exists trusted_devices_user_history_idx
    on beekeep_private.trusted_devices (user_id, created_at desc);

create table if not exists beekeep_private.device_access_requests (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    session_id uuid not null,
    key_fingerprint text not null
        check (key_fingerprint ~ '^[0-9a-f]{64}$'),
    public_key text not null
        check (length(public_key) between 80 and 4096),
    device_label text not null
        check (length(btrim(device_label)) between 1 and 80),
    request_status text not null default 'pending'
        check (request_status in ('pending', 'approved', 'denied', 'expired', 'replaced')),
    approval_method text
        check (approval_method in ('trusted_device', 'email_recovery', 'first_device_setup')),
    created_at timestamptz not null default clock_timestamp(),
    expires_at timestamptz not null default (clock_timestamp() + interval '5 minutes'),
    decided_at timestamptz,
    decided_by_device_id uuid references beekeep_private.trusted_devices(id) on delete set null,
    constraint device_access_requests_session_id_key unique (session_id),
    constraint device_access_requests_expiry_check check (
        expires_at > created_at and expires_at <= created_at + interval '5 minutes'
    ),
    constraint device_access_requests_decision_check check (
        (request_status = 'pending' and decided_at is null)
        or (request_status <> 'pending' and decided_at is not null)
    )
);

create index if not exists device_access_requests_pending_user_idx
    on beekeep_private.device_access_requests (user_id, created_at desc)
    where request_status = 'pending';

alter table beekeep_private.trusted_devices enable row level security;
alter table beekeep_private.device_access_requests enable row level security;

revoke all on table beekeep_private.trusted_devices
    from public, anon, authenticated, service_role;
revoke all on table beekeep_private.device_access_requests
    from public, anon, authenticated, service_role;

-- This function is the one trusted-session predicate used by future table,
-- Storage, and SECURITY DEFINER guards. A JWT alone is not enough: its session_id
-- must still exist in auth.sessions and be bound to the one active device row.
create or replace function public.beekeep_device_session_is_trusted()
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    session_claim text := (select auth.jwt() ->> 'session_id');
    caller_session_id uuid;
begin
    if caller_id is null
       or session_claim is null
       or session_claim !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' then
        return false;
    end if;

    caller_session_id := session_claim::uuid;

    return exists (
        select 1
        from auth.sessions s
        join beekeep_private.trusted_devices d
          on d.session_id = s.id
         and d.user_id = s.user_id
        where s.id = caller_session_id
          and s.user_id = caller_id
          and (s.not_after is null or s.not_after > statement_timestamp())
          and d.device_status = 'active'
          and d.revoked_at is null
    );
end;
$function$;

revoke all on function public.beekeep_device_session_is_trusted()
    from public, anon;
grant execute on function public.beekeep_device_session_is_trusted()
    to authenticated;

create or replace function public.beekeep_require_trusted_device()
returns void
language plpgsql
stable
security invoker
set search_path = ''
as $function$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'This BeeKeep phone is not approved for cloud access.'
            using errcode = '42501',
                  detail = 'BEEKEEP_DEVICE_NOT_APPROVED';
    end if;
end;
$function$;

revoke all on function public.beekeep_require_trusted_device()
    from public, anon;
grant execute on function public.beekeep_require_trusted_device()
    to authenticated;

comment on schema beekeep_private is
    'Non-exposed BeeKeep security records. Access only through narrowly scoped server-side functions.';
comment on table beekeep_private.trusted_devices is
    'One currently active Android installation per account, bound to a Supabase Auth session_id.';
comment on table beekeep_private.device_access_requests is
    'Short-lived pending requests for a replacement or first trusted BeeKeep installation.';
comment on function public.beekeep_device_session_is_trusted() is
    'Returns true only when the current JWT session_id exists in auth.sessions and is bound to the account''s active trusted installation.';

commit;
