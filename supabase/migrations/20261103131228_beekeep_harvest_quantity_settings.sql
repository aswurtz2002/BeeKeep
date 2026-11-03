-- Store the harvest quantity interpretation independently from hive documents.
-- Personal settings are owned by the signed-in user; team settings are shared with
-- every workspace member but are writable only by Owners/Admins while the owner
-- has an active Commercial entitlement.

begin;

create table if not exists public.beekeep_action_settings (
    scope_key text primary key,
    owner_user_id uuid references auth.users(id) on delete cascade,
    workspace_id uuid references public.beekeep_workspaces(id) on delete cascade,
    harvest_quantity_mode text not null default 'per_hive'
        check (harvest_quantity_mode in ('per_hive', 'apiary_total')),
    updated_at bigint not null default
        ((extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint),
    updated_by uuid references auth.users(id) on delete set null,
    constraint beekeep_action_settings_scope_shape check (
        (workspace_id is null
            and owner_user_id is not null
            and scope_key = 'personal:' || owner_user_id::text)
        or
        (workspace_id is not null
            and owner_user_id is null
            and scope_key = 'workspace:' || workspace_id::text)
    )
);

alter table public.beekeep_action_settings enable row level security;

revoke all on table public.beekeep_action_settings from anon, authenticated, public;
grant select, insert, update on table public.beekeep_action_settings to authenticated;

-- Supabase changed its public-schema Data API defaults for new tables in 2026.
-- Explicit grants expose only signed-in access; RLS still gates every row below.
-- Authenticated clients need this table for workspace settings on each device.
create policy "BeeKeep users can read action settings in their scope"
on public.beekeep_action_settings
for select
to authenticated
using (
    (workspace_id is null and owner_user_id = (select auth.uid()))
    or
    (workspace_id is not null and public.is_beekeep_workspace_member(workspace_id))
);

create policy "Personal owners and workspace admins can create action settings"
on public.beekeep_action_settings
for insert
to authenticated
with check (
    updated_by = (select auth.uid())
    and (
        (workspace_id is null
            and owner_user_id = (select auth.uid())
            and scope_key = 'personal:' || (select auth.uid())::text)
        or
        (workspace_id is not null
            and owner_user_id is null
            and scope_key = 'workspace:' || workspace_id::text
            and public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin']::text[])
            and public.beekeep_workspace_owner_has_active_ultimate(workspace_id))
    )
);

create policy "Personal owners and workspace admins can update action settings"
on public.beekeep_action_settings
for update
to authenticated
using (
    (workspace_id is null and owner_user_id = (select auth.uid()))
    or
    (workspace_id is not null
        and public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin']::text[])
        and public.beekeep_workspace_owner_has_active_ultimate(workspace_id))
)
with check (
    updated_by = (select auth.uid())
    and (
        (workspace_id is null
            and owner_user_id = (select auth.uid())
            and scope_key = 'personal:' || (select auth.uid())::text)
        or
        (workspace_id is not null
            and owner_user_id is null
            and scope_key = 'workspace:' || workspace_id::text
            and public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin']::text[])
            and public.beekeep_workspace_owner_has_active_ultimate(workspace_id))
    )
);

create or replace function public.beekeep_action_settings_set_audit()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
    if (select auth.uid()) is null then
        raise exception 'Sign in before changing BeeKeep action settings.'
            using errcode = '42501';
    end if;

    new.updated_by := (select auth.uid());
    new.updated_at := (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint;
    return new;
end;
$$;

revoke all on function public.beekeep_action_settings_set_audit() from public, anon, authenticated;

drop trigger if exists beekeep_action_settings_audit on public.beekeep_action_settings;
create trigger beekeep_action_settings_audit
before insert or update on public.beekeep_action_settings
for each row execute function public.beekeep_action_settings_set_audit();

comment on table public.beekeep_action_settings is
    'BeeKeep personal and workspace action settings. Workspace rows are readable by members and editable only by owners/admins.';

-- Workspace settings changes should wake every device using the selected workspace.
-- Only add the table if the standard Supabase Realtime publication is present and
-- does not already contain it.
do $publication$
begin
    if exists (select 1 from pg_catalog.pg_publication where pubname = 'supabase_realtime')
       and not exists (
           select 1
           from pg_catalog.pg_publication_tables
           where pubname = 'supabase_realtime'
             and schemaname = 'public'
             and tablename = 'beekeep_action_settings'
       )
    then
        execute 'alter publication supabase_realtime add table public.beekeep_action_settings';
    end if;
end;
$publication$;

commit;
