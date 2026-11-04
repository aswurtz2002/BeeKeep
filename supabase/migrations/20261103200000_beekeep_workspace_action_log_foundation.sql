-- BeeKeep Workspace Actions: owner-only audit log foundation.
-- This migration creates only log metadata and retention configuration. It does not
-- read, rewrite, delete, or otherwise mutate hive/apiary/inspection/business records.
-- Automatic event creation is added in the next implementation stage.

begin;

-- ---------------------------------------------------------------------------
-- 1. Owner-controlled retention setting. Missing rows default to Keep forever.
-- ---------------------------------------------------------------------------

create table if not exists public.beekeep_workspace_action_log_settings (
    workspace_id uuid primary key
        references public.beekeep_workspaces(id) on delete cascade,
    retention_mode text not null default 'keep'
        check (retention_mode in ('keep', 'expire_90_days')),
    updated_at timestamptz not null default pg_catalog.clock_timestamp(),
    updated_by uuid references auth.users(id) on delete set null
);

alter table public.beekeep_workspace_action_log_settings enable row level security;

revoke all on table public.beekeep_workspace_action_log_settings
    from public, anon, authenticated;
grant select on table public.beekeep_workspace_action_log_settings to authenticated;

drop policy if exists "Workspace action-log retention is owner-only"
    on public.beekeep_workspace_action_log_settings;
create policy "Workspace action-log retention is owner-only"
on public.beekeep_workspace_action_log_settings
for select
to authenticated
using (
    exists (
        select 1
        from public.beekeep_workspaces w
        where w.id = workspace_id
          and w.is_personal = false
          and w.owner_user_id = (select auth.uid())
          and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    )
);

create or replace function public.beekeep_workspace_action_log_seed_settings()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if new.is_personal = false then
        insert into public.beekeep_workspace_action_log_settings (
            workspace_id, retention_mode
        )
        values (new.id, 'keep')
        on conflict (workspace_id) do nothing;
    end if;
    return new;
end;
$function$;

revoke all on function public.beekeep_workspace_action_log_seed_settings()
    from public, anon, authenticated;

drop trigger if exists beekeep_workspace_action_log_seed_settings
    on public.beekeep_workspaces;
create trigger beekeep_workspace_action_log_seed_settings
after insert on public.beekeep_workspaces
for each row
execute function public.beekeep_workspace_action_log_seed_settings();

-- Backfill settings for existing team workspaces. No business records are touched.
insert into public.beekeep_workspace_action_log_settings (workspace_id, retention_mode)
select w.id, 'keep'
from public.beekeep_workspaces w
where w.is_personal = false
on conflict (workspace_id) do nothing;

-- ---------------------------------------------------------------------------
-- 2. Append-only activity log. Rows have no foreign key to business records,
--    so log retention/deletion cannot cascade into hive or apiary data.
-- ---------------------------------------------------------------------------

create table if not exists public.beekeep_workspace_action_log (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null
        references public.beekeep_workspaces(id) on delete cascade,
    client_action_id uuid not null,
    user_id uuid references auth.users(id) on delete set null,
    username text not null
        check (length(pg_catalog.btrim(username)) between 1 and 32),
    action_type text not null
        check (action_type in (
            'inspection_created',
            'inspection_edited',
            'apiary_added',
            'apiary_edited',
            'apiary_deleted',
            'hive_added',
            'hive_edited',
            'hive_deleted',
            'mass_feed',
            'mass_treatment',
            'mass_harvest'
        )),
    target_type text not null
        check (target_type in ('inspection', 'apiary', 'hive', 'batch')),
    target_id text,
    description text not null
        check (length(pg_catalog.btrim(description)) between 1 and 280),
    action_at timestamptz not null,
    created_at timestamptz not null default pg_catalog.clock_timestamp(),
    deleted_at timestamptz,
    constraint beekeep_workspace_action_log_client_action_unique
        unique (workspace_id, client_action_id)
);

comment on table public.beekeep_workspace_action_log is
    'Owner-only, append-only team activity log. Soft deletion applies to log rows only.';
comment on column public.beekeep_workspace_action_log.client_action_id is
    'Stable client UUID used by the server write path to make offline replay idempotent.';
comment on column public.beekeep_workspace_action_log.action_at is
    'Original action time, supplied by the durable offline queue and displayed in local time.';
comment on column public.beekeep_workspace_action_log.created_at is
    'Server receipt time; differs from action_at when an action was synchronized offline.';
comment on column public.beekeep_workspace_action_log.target_id is
    'Snapshot identifier only. Deliberately has no foreign key to hive/apiary/inspection records.';

create index if not exists beekeep_workspace_action_log_created_desc_idx
    on public.beekeep_workspace_action_log (workspace_id, created_at desc, id desc);

create index if not exists beekeep_workspace_action_log_action_desc_idx
    on public.beekeep_workspace_action_log (workspace_id, action_at desc, id desc)
    where deleted_at is null;

alter table public.beekeep_workspace_action_log enable row level security;

revoke all on table public.beekeep_workspace_action_log
    from public, anon, authenticated;
grant select on table public.beekeep_workspace_action_log to authenticated;

drop policy if exists "Only the team owner can read active workspace actions"
    on public.beekeep_workspace_action_log;
create policy "Only the team owner can read active workspace actions"
on public.beekeep_workspace_action_log
for select
to authenticated
using (
    deleted_at is null
    and exists (
        select 1
        from public.beekeep_workspaces w
        where w.id = workspace_id
          and w.is_personal = false
          and w.owner_user_id = (select auth.uid())
          and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    )
);

-- ---------------------------------------------------------------------------
-- 3. Owner-only RPCs. Clients have no direct INSERT/UPDATE/DELETE table grants.
-- ---------------------------------------------------------------------------

create or replace function public.beekeep_workspace_action_log_set_retention(
    target_workspace_id uuid,
    target_retention_mode text
)
returns text
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    cleaned_mode text := pg_catalog.lower(pg_catalog.btrim(coalesce(target_retention_mode, '')));
begin
    if caller_id is null then
        raise exception 'Sign in before changing workspace action retention.'
            using errcode = '42501';
    end if;

    if cleaned_mode not in ('keep', 'expire_90_days') then
        raise exception 'Choose Keep Workspace Entries or expire after 90 days.'
            using errcode = '22023';
    end if;

    perform 1
    from public.beekeep_workspaces w
    where w.id = target_workspace_id
      and w.is_personal = false
      and w.owner_user_id = caller_id
      and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    for update;

    if not found then
        raise exception 'Only the owner of this team workspace can change action retention.'
            using errcode = '42501';
    end if;

    insert into public.beekeep_workspace_action_log_settings (
        workspace_id, retention_mode, updated_at, updated_by
    )
    values (
        target_workspace_id, cleaned_mode, pg_catalog.clock_timestamp(), caller_id
    )
    on conflict (workspace_id) do update
       set retention_mode = excluded.retention_mode,
           updated_at = excluded.updated_at,
           updated_by = excluded.updated_by;

    return cleaned_mode;
end;
$function$;

create or replace function public.beekeep_workspace_action_log_delete(
    target_entry_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    target_workspace_id uuid;
begin
    if caller_id is null then
        raise exception 'Sign in before deleting a workspace action entry.'
            using errcode = '42501';
    end if;

    select l.workspace_id
      into target_workspace_id
      from public.beekeep_workspace_action_log l
     where l.id = target_entry_id;

    if not found then
        return false;
    end if;

    perform 1
    from public.beekeep_workspaces w
    where w.id = target_workspace_id
      and w.is_personal = false
      and w.owner_user_id = caller_id
      and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    for update;

    if not found then
        raise exception 'Only the workspace owner can delete action-log entries.'
            using errcode = '42501';
    end if;

    update public.beekeep_workspace_action_log
       set deleted_at = pg_catalog.clock_timestamp()
     where id = target_entry_id
       and workspace_id = target_workspace_id
       and deleted_at is null;

    return found;
end;
$function$;

create or replace function public.beekeep_workspace_action_log_delete_all(
    target_workspace_id uuid
)
returns integer
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    affected_count integer := 0;
begin
    if caller_id is null then
        raise exception 'Sign in before deleting workspace action entries.'
            using errcode = '42501';
    end if;

    perform 1
    from public.beekeep_workspaces w
    where w.id = target_workspace_id
      and w.is_personal = false
      and w.owner_user_id = caller_id
      and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    for update;

    if not found then
        raise exception 'Only the workspace owner can delete action-log entries.'
            using errcode = '42501';
    end if;

    update public.beekeep_workspace_action_log
       set deleted_at = pg_catalog.clock_timestamp()
     where workspace_id = target_workspace_id
       and deleted_at is null;

    get diagnostics affected_count = row_count;
    return affected_count;
end;
$function$;

-- Runs as a private scheduled task. Retention is based on the original action time,
-- so an offline action older than 90 days is expired as soon as it is synchronized.
create or replace function public.beekeep_workspace_action_log_expire()
returns integer
language plpgsql
security definer
set search_path = ''
as $function$
declare
    affected_count integer := 0;
begin
    update public.beekeep_workspace_action_log l
       set deleted_at = pg_catalog.clock_timestamp()
      from public.beekeep_workspace_action_log_settings s
     where s.workspace_id = l.workspace_id
       and s.retention_mode = 'expire_90_days'
       and l.deleted_at is null
       and l.action_at < pg_catalog.statement_timestamp() - interval '90 days';

    get diagnostics affected_count = row_count;
    return affected_count;
end;
$function$;

revoke all on function public.beekeep_workspace_action_log_set_retention(uuid, text)
    from public, anon, authenticated;
revoke all on function public.beekeep_workspace_action_log_delete(uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_workspace_action_log_delete_all(uuid)
    from public, anon, authenticated;
revoke all on function public.beekeep_workspace_action_log_expire()
    from public, anon, authenticated;

grant execute on function public.beekeep_workspace_action_log_set_retention(uuid, text)
    to authenticated;
grant execute on function public.beekeep_workspace_action_log_delete(uuid)
    to authenticated;
grant execute on function public.beekeep_workspace_action_log_delete_all(uuid)
    to authenticated;

-- ---------------------------------------------------------------------------
-- 4. Daily database cleanup. pg_cron is enabled only when the project offers it;
--    the expiry RPC remains manually testable on plain PostgreSQL CI databases.
-- ---------------------------------------------------------------------------

do $scheduler$
begin
    if exists (
        select 1
        from pg_catalog.pg_available_extensions
        where name = 'pg_cron'
    ) then
        if not exists (
            select 1 from pg_catalog.pg_extension where extname = 'pg_cron'
        ) then
            execute 'create extension pg_cron with schema pg_catalog';
        end if;

        execute 'grant usage on schema cron to postgres';
        execute 'grant all privileges on all tables in schema cron to postgres';
        execute $unschedule$
            select cron.unschedule(jobid)
            from cron.job
            where jobname = 'beekeep-workspace-action-log-expiry'
        $unschedule$;
        execute $schedule$
            select cron.schedule(
                'beekeep-workspace-action-log-expiry',
                '17 4 * * *',
                'select public.beekeep_workspace_action_log_expire()'
            )
        $schedule$;
    end if;
end;
$scheduler$;

commit;
