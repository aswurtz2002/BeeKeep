-- Extend trusted custom hive-limit exceptions to active Pro and Ultimate customers.
-- Existing customer override values remain valid. Free, expired, and downgraded accounts
-- continue to resolve to their current base limit and never receive extra capacity.
begin;

-- Replace the original Ultimate-only lower bound without depending on PostgreSQL's generated
-- name for an inline CHECK constraint.
do $constraint_update$
declare
    check_row record;
begin
    for check_row in
        select c.conname
          from pg_catalog.pg_constraint c
         where c.conrelid = 'public.beekeep_user_hive_limit_overrides'::regclass
           and c.contype = 'c'
           and pg_catalog.pg_get_constraintdef(c.oid) ilike '%hive_limit_override%'
    loop
        execute pg_catalog.format(
            'alter table public.beekeep_user_hive_limit_overrides drop constraint %I',
            check_row.conname
        );
    end loop;
end;
$constraint_update$;

alter table public.beekeep_user_hive_limit_overrides
    add constraint beekeep_hive_limit_override_range
    check (hive_limit_override between 1000 and 1000000);

-- Keep an immutable support audit trail, including removals (which delete the current override row).
create table if not exists public.beekeep_hive_limit_override_audit (
    id bigint generated always as identity primary key,
    target_user_id uuid not null references auth.users(id) on delete cascade,
    actor_user_id uuid references auth.users(id) on delete set null,
    action text not null check (action in ('set', 'clear')),
    old_hive_limit integer,
    new_hive_limit integer,
    reason text not null check (length(trim(reason)) between 3 and 500),
    created_at timestamptz not null default now()
);

create index if not exists beekeep_hive_limit_override_audit_target_created_idx
    on public.beekeep_hive_limit_override_audit (target_user_id, created_at desc);

alter table public.beekeep_hive_limit_override_audit enable row level security;
revoke all on table public.beekeep_hive_limit_override_audit from public, anon, authenticated;
grant all on table public.beekeep_hive_limit_override_audit to service_role;

-- A custom limit applies only when the trusted server-side plan currently has a paid hive
-- allowance (Pro >= 1,000 or Ultimate >= 10,000). Expired/downgraded accounts ignore it.
create or replace function public.beekeep_resolved_hive_limit(target_user_id uuid)
returns integer
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    base_limit integer;
    custom_limit integer;
begin
    base_limit := public.beekeep_server_base_hive_limit(target_user_id);
    if base_limit < 1000 then
        return base_limit;
    end if;

    select o.hive_limit_override
      into custom_limit
      from public.beekeep_user_hive_limit_overrides o
     where o.user_id = target_user_id;

    return greatest(base_limit, coalesce(custom_limit, base_limit));
end;
$function$;

revoke all on function public.beekeep_resolved_hive_limit(uuid)
    from public, anon, authenticated;

-- The mobile RPC exposes only the signed-in account's own paid-plan override.
create or replace function public.beekeep_my_hive_limit_override()
returns table(hive_limit_override integer)
language sql
stable
security definer
set search_path = ''
as $function$
    select case
        when auth.uid() is not null
         and public.beekeep_server_base_hive_limit(auth.uid()) >= 1000
        then (
            select o.hive_limit_override
              from public.beekeep_user_hive_limit_overrides o
             where o.user_id = auth.uid()
        )
        else null
    end::integer as hive_limit_override;
$function$;

revoke all on function public.beekeep_my_hive_limit_override()
    from public, anon;
grant execute on function public.beekeep_my_hive_limit_override() to authenticated;

-- Expose the current user's trusted plan and resolved quota so the mobile UI displays the
-- same plan/cap that the database enforces. The user cannot select another account ID.
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
            when base.base_hive_limit >= 1000
                then coalesce(nullif(u.raw_app_meta_data ->> 'beekeep_plan', ''), 'free')
            else 'free'
        end::text as plan_code,
        base.base_hive_limit,
        case
            when base.base_hive_limit >= 1000 then o.hive_limit_override
            else null
        end::integer as custom_hive_limit,
        public.beekeep_resolved_hive_limit(u.id) as effective_hive_limit,
        case
            when base.base_hive_limit >= 1000
                then nullif(u.raw_app_meta_data ->> 'beekeep_subscription_expires_at', '')
            else null
        end::text as expires_at,
        (base.base_hive_limit >= 1000) as is_active_paid_plan,
        public.beekeep_user_has_active_ultimate(u.id) as is_active_ultimate
    from auth.users u
    cross join lateral (
        select public.beekeep_server_base_hive_limit(u.id) as base_hive_limit
    ) base
    left join public.beekeep_user_hive_limit_overrides o
        on o.user_id = u.id
    where u.id = auth.uid()
$function$;

revoke all on function public.beekeep_my_hive_entitlement()
    from public, anon;
grant execute on function public.beekeep_my_hive_entitlement() to authenticated;

-- The return contract expands for Pro support, account status, and audit history. Drop the
-- prior signature first because PostgreSQL cannot CREATE OR REPLACE a function with new OUT columns.
drop function if exists public.beekeep_admin_manage_hive_limit_override(text, text, integer, text, uuid);

create function public.beekeep_admin_manage_hive_limit_override(
    p_action text,
    p_email text,
    p_hive_limit integer default null,
    p_reason text default null,
    p_actor_id uuid default null
)
returns table (
    account_id uuid,
    account_email text,
    plan_code text,
    expires_at text,
    is_active_paid_plan boolean,
    is_active_ultimate boolean,
    base_hive_limit integer,
    custom_hive_limit integer,
    override_reason text,
    override_updated_at timestamptz,
    override_updated_by uuid,
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
    v_base_limit integer;
    v_is_active_paid_plan boolean;
    v_is_active_ultimate boolean;
    v_old_limit integer;
    v_old_reason text;
    v_action_reason text;
begin
    v_action := pg_catalog.lower(pg_catalog.btrim(coalesce(p_action, '')));
    if v_action not in ('lookup', 'set', 'clear') then
        raise exception 'Unsupported support-admin action.'
            using errcode = '22023';
    end if;

    if p_email is null
       or pg_catalog.length(pg_catalog.btrim(p_email)) < 3
       or pg_catalog.length(pg_catalog.btrim(p_email)) > 254
       or pg_catalog.strpos(pg_catalog.btrim(p_email), '@') < 2 then
        raise exception 'Enter a valid account email.'
            using errcode = '22023';
    end if;

    -- Exact case-insensitive email matching; never return a customer list.
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

    v_base_limit := public.beekeep_server_base_hive_limit(v_target_user_id);
    v_is_active_paid_plan := v_base_limit >= 1000;
    v_is_active_ultimate := public.beekeep_user_has_active_ultimate(v_target_user_id);

    if v_action in ('set', 'clear') and p_actor_id is null then
        raise exception 'The support-admin actor is required for the audit trail.'
            using errcode = '22023';
    end if;

    if v_action = 'set' then
        if not v_is_active_paid_plan then
            raise exception 'A custom hive limit can only be assigned to an active Pro or Ultimate account.'
                using errcode = '22023';
        end if;
        if p_hive_limit is null
           or p_hive_limit < v_base_limit
           or p_hive_limit > 1000000 then
            raise exception 'This account''s custom hive limit must be at least % and no more than 1,000,000.',
                v_base_limit
                using errcode = '22023';
        end if;
        if p_reason is null
           or pg_catalog.length(pg_catalog.btrim(p_reason)) < 3
           or pg_catalog.length(pg_catalog.btrim(p_reason)) > 500 then
            raise exception 'Enter a reason between 3 and 500 characters.'
                using errcode = '22023';
        end if;

        select o.hive_limit_override, o.reason
          into v_old_limit, v_old_reason
          from public.beekeep_user_hive_limit_overrides o
         where o.user_id = v_target_user_id;

        insert into public.beekeep_user_hive_limit_overrides
            (user_id, hive_limit_override, reason, updated_by)
        values
            (v_target_user_id, p_hive_limit, pg_catalog.btrim(p_reason), p_actor_id)
        on conflict (user_id) do update
            set hive_limit_override = excluded.hive_limit_override,
                reason = excluded.reason,
                updated_by = excluded.updated_by,
                updated_at = pg_catalog.now();

        v_action_reason := pg_catalog.btrim(p_reason);
        insert into public.beekeep_hive_limit_override_audit
            (target_user_id, actor_user_id, action, old_hive_limit, new_hive_limit, reason)
        values
            (v_target_user_id, p_actor_id, 'set', v_old_limit, p_hive_limit, v_action_reason);

    elsif v_action = 'clear' then
        select o.hive_limit_override, o.reason
          into v_old_limit, v_old_reason
          from public.beekeep_user_hive_limit_overrides o
         where o.user_id = v_target_user_id;

        if v_old_limit is not null then
            v_action_reason := coalesce(
                nullif(pg_catalog.btrim(p_reason), ''),
                nullif(pg_catalog.btrim(v_old_reason), ''),
                'Support admin removed the custom hive limit'
            );
            insert into public.beekeep_hive_limit_override_audit
                (target_user_id, actor_user_id, action, old_hive_limit, new_hive_limit, reason)
            values
                (v_target_user_id, p_actor_id, 'clear', v_old_limit, null, v_action_reason);

            delete from public.beekeep_user_hive_limit_overrides o
             where o.user_id = v_target_user_id;
        end if;
    end if;

    return query
    select
        v_target_user_id,
        v_target_email,
        coalesce(v_metadata ->> 'beekeep_plan', 'free'),
        nullif(v_metadata ->> 'beekeep_subscription_expires_at', ''),
        v_is_active_paid_plan,
        v_is_active_ultimate,
        v_base_limit,
        o.hive_limit_override,
        o.reason,
        o.updated_at,
        o.updated_by,
        coalesce((
            select pg_catalog.jsonb_agg(
                pg_catalog.jsonb_build_object(
                    'id', recent.id,
                    'action', recent.action,
                    'old_hive_limit', recent.old_hive_limit,
                    'new_hive_limit', recent.new_hive_limit,
                    'reason', recent.reason,
                    'actor_user_id', recent.actor_user_id,
                    'created_at', recent.created_at
                ) order by recent.created_at desc
            )
              from (
                    select a.id, a.action, a.old_hive_limit, a.new_hive_limit,
                           a.reason, a.actor_user_id, a.created_at
                      from public.beekeep_hive_limit_override_audit a
                     where a.target_user_id = v_target_user_id
                     order by a.created_at desc, a.id desc
                     limit 10
              ) recent
        ), '[]'::jsonb)
      from (select 1) as singleton
      left join public.beekeep_user_hive_limit_overrides o
        on o.user_id = v_target_user_id;
end;
$function$;

revoke all on function public.beekeep_admin_manage_hive_limit_override(text, text, integer, text, uuid)
    from public, anon, authenticated;
grant execute on function public.beekeep_admin_manage_hive_limit_override(text, text, integer, text, uuid)
    to service_role;

commit;
