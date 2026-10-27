-- Per-account Ultimate hive limit exceptions.
-- Only trusted server-side billing metadata can qualify an account for Ultimate.
-- The override table is deliberately unreadable/writable by mobile authenticated users;
-- support/admin operators manage it through the trusted database console or a protected
-- server-side admin tool.

begin;

create table if not exists public.beekeep_user_hive_limit_overrides (
    user_id uuid primary key references auth.users(id) on delete cascade,
    hive_limit_override integer not null
        check (hive_limit_override between 10000 and 1000000),
    reason text not null check (length(trim(reason)) between 3 and 500),
    updated_by uuid references auth.users(id) on delete set null,
    updated_at timestamptz not null default now()
);

alter table public.beekeep_user_hive_limit_overrides enable row level security;
revoke all on table public.beekeep_user_hive_limit_overrides from public, anon, authenticated;
grant all on table public.beekeep_user_hive_limit_overrides to service_role;

create or replace function public.beekeep_touch_hive_limit_override_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $function$
begin
    new.updated_at := now();
    return new;
end;
$function$;

drop trigger if exists beekeep_touch_hive_limit_override_updated_at
    on public.beekeep_user_hive_limit_overrides;
create trigger beekeep_touch_hive_limit_override_updated_at
before update on public.beekeep_user_hive_limit_overrides
for each row execute function public.beekeep_touch_hive_limit_override_updated_at();

revoke all on function public.beekeep_touch_hive_limit_override_updated_at()
    from public, anon, authenticated;

-- Returns the plan's ordinary server-trusted hive allowance. Subscription plan and expiry
-- values are read from raw_app_meta_data only; raw_user_meta_data is never an authority.
create or replace function public.beekeep_server_base_hive_limit(target_user_id uuid)
returns integer
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
        return 150;
    end if;

    if public.beekeep_user_has_active_ultimate(target_user_id) then
        return 10000;
    end if;

    select coalesce(u.raw_app_meta_data, '{}'::jsonb)
      into metadata
      from auth.users u
     where u.id = target_user_id;

    if not found then
        return 150;
    end if;

    normalized_plan := regexp_replace(
        lower(coalesce(metadata ->> 'beekeep_plan', '')),
        '[^a-z]',
        '',
        'g'
    );

    if normalized_plan not in ('pro', 'promonthly', 'proyearly') then
        return 150;
    end if;

    expiry_text := nullif(trim(metadata ->> 'beekeep_subscription_expires_at'), '');
    if expiry_text is null then
        return 150;
    end if;

    begin
        expiry_value := expiry_text::timestamptz;
    exception when others then
        return 150;
    end;

    if expiry_value > now() then
        return 1000;
    end if;

    return 150;
end;
$function$;

-- Use an override only while the account has an active, trusted Ultimate entitlement.
-- Thus a forgotten override does not keep extra capacity after an account expires/downgrades.
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
    if base_limit < 10000 then
        return base_limit;
    end if;

    select o.hive_limit_override
      into custom_limit
      from public.beekeep_user_hive_limit_overrides o
     where o.user_id = target_user_id;

    return coalesce(custom_limit, base_limit);
end;
$function$;

revoke all on function public.beekeep_server_base_hive_limit(uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_resolved_hive_limit(uuid)
    from public, anon, authenticated;

-- Exposes only the current user's own active Ultimate override. No user ID is accepted,
-- and a Free/Pro/expired account always receives null.
create or replace function public.beekeep_my_hive_limit_override()
returns table(hive_limit_override integer)
language sql
stable
security definer
set search_path = ''
as $function$
    select case
        when auth.uid() is not null
         and public.beekeep_user_has_active_ultimate(auth.uid())
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

-- Enforce limits at cloud sync as well as on-device creation. The existing active hive row
-- may always be updated; the guard applies only when a new active hive record is introduced.
create or replace function public.beekeep_enforce_personal_hive_limit()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    account_limit integer;
    existing_count bigint;
begin
    if new.entity_type <> 'hive' or new.deleted then
        return new;
    end if;

    if tg_op = 'UPDATE'
       and old.entity_type = 'hive'
       and not old.deleted
       and old.user_id = new.user_id
       and old.entity_id = new.entity_id then
        return new;
    end if;

    account_limit := public.beekeep_resolved_hive_limit(new.user_id);
    select count(*)
      into existing_count
      from public.beekeep_documents d
     where d.user_id = new.user_id
       and d.entity_type = 'hive'
       and not d.deleted;

    if existing_count >= account_limit then
        raise exception 'Hive limit reached: this account has a % hive limit.', account_limit
            using errcode = '23514';
    end if;

    return new;
end;
$function$;

drop trigger if exists beekeep_enforce_personal_hive_limit on public.beekeep_documents;
create trigger beekeep_enforce_personal_hive_limit
before insert or update on public.beekeep_documents
for each row execute function public.beekeep_enforce_personal_hive_limit();

revoke all on function public.beekeep_enforce_personal_hive_limit()
    from public, anon, authenticated;

create or replace function public.beekeep_enforce_workspace_hive_limit()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    owner_id uuid;
    account_limit integer;
    existing_count bigint;
begin
    if new.entity_type <> 'hive' or new.deleted then
        return new;
    end if;

    if tg_op = 'UPDATE'
       and old.entity_type = 'hive'
       and not old.deleted
       and old.workspace_id = new.workspace_id
       and old.entity_id = new.entity_id then
        return new;
    end if;

    select w.owner_user_id
      into owner_id
      from public.beekeep_workspaces w
     where w.id = new.workspace_id
       and not w.is_personal;

    if not found then
        -- Personal workspaces use beekeep_documents and its own trigger.
        return new;
    end if;

    account_limit := public.beekeep_resolved_hive_limit(owner_id);
    select count(*)
      into existing_count
      from public.beekeep_workspace_documents d
     where d.workspace_id = new.workspace_id
       and d.entity_type = 'hive'
       and not d.deleted;

    if existing_count >= account_limit then
        raise exception 'Hive limit reached: this team workspace has a % hive limit.', account_limit
            using errcode = '23514';
    end if;

    return new;
end;
$function$;

drop trigger if exists beekeep_enforce_workspace_hive_limit on public.beekeep_workspace_documents;
create trigger beekeep_enforce_workspace_hive_limit
before insert or update on public.beekeep_workspace_documents
for each row execute function public.beekeep_enforce_workspace_hive_limit();

revoke all on function public.beekeep_enforce_workspace_hive_limit()
    from public, anon, authenticated;

commit;
