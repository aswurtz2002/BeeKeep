-- Admin-managed Commercial access grants.
-- These grants are separate from billing metadata and can be permanent or time-limited.
begin;

create table if not exists public.beekeep_admin_commercial_grants (
    user_id uuid primary key references auth.users(id) on delete cascade,
    expires_at timestamptz,
    reason text not null check (pg_catalog.length(pg_catalog.btrim(reason)) between 3 and 500),
    granted_by uuid references auth.users(id) on delete set null,
    created_at timestamptz not null default pg_catalog.now(),
    updated_at timestamptz not null default pg_catalog.now()
);

create table if not exists public.beekeep_admin_commercial_grant_audit (
    id bigint generated always as identity primary key,
    target_user_id uuid not null references auth.users(id) on delete cascade,
    actor_user_id uuid references auth.users(id) on delete set null,
    action text not null check (action in ('grant', 'revoke')),
    old_expires_at timestamptz,
    new_expires_at timestamptz,
    reason text not null check (pg_catalog.length(pg_catalog.btrim(reason)) between 3 and 500),
    created_at timestamptz not null default pg_catalog.now()
);

create index if not exists beekeep_admin_commercial_grant_audit_target_created_idx
    on public.beekeep_admin_commercial_grant_audit (target_user_id, created_at desc);

alter table public.beekeep_admin_commercial_grants enable row level security;
alter table public.beekeep_admin_commercial_grant_audit enable row level security;
revoke all on table public.beekeep_admin_commercial_grants
    from public, anon, authenticated, service_role;
revoke all on table public.beekeep_admin_commercial_grant_audit
    from public, anon, authenticated, service_role;

-- A manual grant is an additional trusted source of Commercial access.
-- Existing billing metadata remains authoritative for paid purchases; a manual grant
-- can enable Commercial even for Hobbyist/Free accounts without rewriting that metadata.
create or replace function public.beekeep_user_has_active_commercial(target_user_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    metadata jsonb;
    normalized_plan text;
    expiry_text text;
    expiry_value timestamptz;
begin
    if target_user_id is null then
        return false;
    end if;

    if exists (
        select 1
          from public.beekeep_admin_commercial_grants g
         where g.user_id = target_user_id
           and (g.expires_at is null or g.expires_at > pg_catalog.now())
    ) then
        return true;
    end if;

    select coalesce(u.raw_app_meta_data, '{}'::jsonb)
      into metadata
      from auth.users u
     where u.id = target_user_id;

    if not found then
        return false;
    end if;

    normalized_plan := pg_catalog.regexp_replace(
        pg_catalog.lower(coalesce(metadata ->> 'beekeep_plan', '')),
        '[^a-z]',
        '',
        'g'
    );

    -- Commercial is canonical. Legacy Ultimate remains a compatibility alias;
    -- legacy Pro deliberately does not receive Commercial access.
    if normalized_plan not in (
        'commercial', 'commercialmonthly', 'commercialyearly',
        'ultimate', 'ultimatemonthly', 'ultimateyearly'
    ) then
        return false;
    end if;

    expiry_text := nullif(pg_catalog.btrim(metadata ->> 'beekeep_subscription_expires_at'), '');
    if expiry_text is null then
        return false;
    end if;

    begin
        expiry_value := expiry_text::timestamptz;
    exception when others then
        return false;
    end;

    return expiry_value > pg_catalog.now();
end;
$function$;

-- The mobile client continues to read only its own effective entitlement.
-- A manual grant reports the Commercial plan while preserving the original billing fields.
create or replace function public.beekeep_my_hive_entitlement()
returns table (
    plan_code text,
    base_hive_limit integer,
    custom_hive_limit integer,
    effective_hive_limit integer,
    expires_at text,
    is_active_paid_plan boolean,
    is_active_ultimate boolean
)
language sql
stable
security definer
set search_path = ''
as $function$
    select
        case
            when base.base_hive_limit >= 10000 then 'commercial'
            else 'hobbyist'
        end::text as plan_code,
        base.base_hive_limit,
        case
            when base.base_hive_limit >= 10000 then o.hive_limit_override
            else null
        end::integer as custom_hive_limit,
        public.beekeep_resolved_hive_limit(u.id) as effective_hive_limit,
        case
            when grant_row.user_id is not null
             and (grant_row.expires_at is null or grant_row.expires_at > pg_catalog.now())
                then grant_row.expires_at::text
            when base.base_hive_limit >= 10000
                then nullif(u.raw_app_meta_data ->> 'beekeep_subscription_expires_at', '')
            else null
        end::text as expires_at,
        (base.base_hive_limit >= 10000) as is_active_paid_plan,
        public.beekeep_user_has_active_commercial(u.id) as is_active_ultimate
    from auth.users u
    cross join lateral (
        select public.beekeep_server_base_hive_limit(u.id) as base_hive_limit
    ) base
    left join public.beekeep_user_hive_limit_overrides o
        on o.user_id = u.id
    left join public.beekeep_admin_commercial_grants grant_row
        on grant_row.user_id = u.id
    where u.id = auth.uid();
$function$;

-- This RPC is callable only by the service-role client inside the authenticated Edge Function.
-- The Edge Function verifies the caller's signed-in JWT and trusted support-admin app_metadata.
create or replace function public.beekeep_admin_manage_commercial_access(
    p_action text,
    p_email text,
    p_expires_in_days integer default null,
    p_reason text default null,
    p_actor_id uuid default null
)
returns table (
    account_id uuid,
    account_email text,
    plan_code text,
    is_active_commercial boolean,
    has_admin_grant boolean,
    admin_grant_active boolean,
    admin_grant_expires_at text,
    admin_grant_reason text,
    admin_grant_updated_at timestamptz,
    admin_granted_by uuid,
    audit_history jsonb
)
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_action text;
    v_target_user_id uuid;
    v_target_email text;
    v_metadata jsonb;
    v_old_expires_at timestamptz;
    v_old_reason text;
    v_new_expires_at timestamptz;
    v_action_reason text;
    v_had_grant boolean := false;
begin
    v_action := pg_catalog.lower(pg_catalog.btrim(coalesce(p_action, '')));
    if v_action not in ('lookup', 'grant', 'revoke') then
        raise exception 'Unsupported Commercial access action.'
            using errcode = '22023';
    end if;

    if p_email is null
       or pg_catalog.length(pg_catalog.btrim(p_email)) < 3
       or pg_catalog.length(pg_catalog.btrim(p_email)) > 254
       or pg_catalog.strpos(pg_catalog.btrim(p_email), '@') < 2 then
        raise exception 'Enter a valid account email.'
            using errcode = '22023';
    end if;

    -- Exact case-insensitive email match; never return a customer list.
    select u.id, u.email, coalesce(u.raw_app_meta_data, '{}'::jsonb)
      into v_target_user_id, v_target_email, v_metadata
      from auth.users u
     where pg_catalog.lower(pg_catalog.btrim(u.email)) =
           pg_catalog.lower(pg_catalog.btrim(p_email))
     limit 1;

    if v_target_user_id is null then
        raise exception 'No BeeKeep account was found with that exact email.'
            using errcode = 'P0002';
    end if;

    if v_action in ('grant', 'revoke') and p_actor_id is null then
        raise exception 'The support-admin actor is required for the audit trail.'
            using errcode = '22023';
    end if;

    if v_action = 'grant' then
        if p_expires_in_days is not null
           and (p_expires_in_days < 1 or p_expires_in_days > 3650) then
            raise exception 'Grant duration must be blank for permanent access or between 1 and 3,650 days.'
                using errcode = '22023';
        end if;
        if p_reason is null
           or pg_catalog.length(pg_catalog.btrim(p_reason)) < 3
           or pg_catalog.length(pg_catalog.btrim(p_reason)) > 500 then
            raise exception 'Enter a reason between 3 and 500 characters.'
                using errcode = '22023';
        end if;

        v_new_expires_at := case
            when p_expires_in_days is null then null
            else pg_catalog.now() + pg_catalog.make_interval(days => p_expires_in_days)
        end;

        select g.expires_at, g.reason
          into v_old_expires_at, v_old_reason
          from public.beekeep_admin_commercial_grants g
         where g.user_id = v_target_user_id;
        v_had_grant := found;

        insert into public.beekeep_admin_commercial_grants
            (user_id, expires_at, reason, granted_by)
        values
            (v_target_user_id, v_new_expires_at, pg_catalog.btrim(p_reason), p_actor_id)
        on conflict (user_id) do update
            set expires_at = excluded.expires_at,
                reason = excluded.reason,
                granted_by = excluded.granted_by,
                updated_at = pg_catalog.now();

        insert into public.beekeep_admin_commercial_grant_audit
            (target_user_id, actor_user_id, action, old_expires_at, new_expires_at, reason)
        values
            (v_target_user_id, p_actor_id, 'grant',
             case when v_had_grant then v_old_expires_at else null end,
             v_new_expires_at, pg_catalog.btrim(p_reason));

    elsif v_action = 'revoke' then
        select g.expires_at, g.reason
          into v_old_expires_at, v_old_reason
          from public.beekeep_admin_commercial_grants g
         where g.user_id = v_target_user_id;
        v_had_grant := found;

        if v_had_grant then
            if p_reason is not null and pg_catalog.btrim(p_reason) <> ''
               and (pg_catalog.length(pg_catalog.btrim(p_reason)) < 3
                    or pg_catalog.length(pg_catalog.btrim(p_reason)) > 500) then
                raise exception 'Enter a reason between 3 and 500 characters, or leave it blank.'
                    using errcode = '22023';
            end if;

            v_action_reason := coalesce(
                nullif(pg_catalog.btrim(p_reason), ''),
                nullif(pg_catalog.btrim(v_old_reason), ''),
                'Support admin revoked the manual Commercial access grant'
            );

            insert into public.beekeep_admin_commercial_grant_audit
                (target_user_id, actor_user_id, action, old_expires_at, new_expires_at, reason)
            values
                (v_target_user_id, p_actor_id, 'revoke', v_old_expires_at, null, v_action_reason);

            delete from public.beekeep_admin_commercial_grants g
             where g.user_id = v_target_user_id;
        end if;
    end if;

    return query
    select
        v_target_user_id,
        v_target_email,
        case
            when public.beekeep_user_has_active_commercial(v_target_user_id) then 'commercial'
            else coalesce(nullif(v_metadata ->> 'beekeep_plan', ''), 'hobbyist')
        end::text,
        public.beekeep_user_has_active_commercial(v_target_user_id),
        (g.user_id is not null),
        (g.user_id is not null and (g.expires_at is null or g.expires_at > pg_catalog.now())),
        g.expires_at::text,
        g.reason,
        g.updated_at,
        g.granted_by,
        coalesce((
            select pg_catalog.jsonb_agg(
                pg_catalog.jsonb_build_object(
                    'id', recent.id,
                    'action', recent.action,
                    'old_expires_at', recent.old_expires_at,
                    'new_expires_at', recent.new_expires_at,
                    'reason', recent.reason,
                    'actor_user_id', recent.actor_user_id,
                    'created_at', recent.created_at
                ) order by recent.created_at desc
            )
            from (
                select a.id, a.action, a.old_expires_at, a.new_expires_at,
                       a.reason, a.actor_user_id, a.created_at
                  from public.beekeep_admin_commercial_grant_audit a
                 where a.target_user_id = v_target_user_id
                 order by a.created_at desc, a.id desc
                 limit 10
            ) recent
        ), '[]'::jsonb)
    from (select 1) singleton
    left join public.beekeep_admin_commercial_grants g
        on g.user_id = v_target_user_id;
end;
$function$;

revoke all on function public.beekeep_admin_manage_commercial_access(text, text, integer, text, uuid)
    from public, anon, authenticated;
grant execute on function public.beekeep_admin_manage_commercial_access(text, text, integer, text, uuid)
    to service_role;

commit;
