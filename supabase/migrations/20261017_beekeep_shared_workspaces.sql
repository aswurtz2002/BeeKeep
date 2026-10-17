-- Stage 1: shared-workspace foundation.
-- Creates a private default workspace for every existing/new auth user and associates
-- their current cloud documents with it. Existing per-user document RLS and conflict keys
-- intentionally remain unchanged in Stage 1: this migration does NOT share records yet.

begin;

create table if not exists public.beekeep_workspaces (
    id uuid primary key default gen_random_uuid(),
    name text not null default 'My BeeKeep Workspace',
    owner_user_id uuid not null references auth.users(id) on delete cascade,
    is_personal boolean not null default true,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint beekeep_workspaces_name_not_blank check (length(trim(name)) > 0)
);

-- A user gets exactly one personal workspace, but can own additional business workspaces later.
create unique index if not exists beekeep_workspaces_one_personal_per_owner_idx
    on public.beekeep_workspaces (owner_user_id)
    where is_personal = true;

create table if not exists public.beekeep_workspace_members (
    workspace_id uuid not null references public.beekeep_workspaces(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    role text not null check (role in ('owner', 'admin', 'member', 'viewer')),
    joined_at timestamptz not null default now(),
    invited_by uuid references auth.users(id) on delete set null,
    primary key (workspace_id, user_id)
);

create index if not exists beekeep_workspace_members_user_id_idx
    on public.beekeep_workspace_members (user_id, workspace_id);

-- Security-definer helpers avoid recursive RLS policies on the membership table.
create or replace function public.is_beekeep_workspace_member(target_workspace_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $function$
    select exists (
        select 1
        from public.beekeep_workspace_members m
        where m.workspace_id = target_workspace_id
          and m.user_id = auth.uid()
    );
$function$;

create or replace function public.has_beekeep_workspace_role(
    target_workspace_id uuid,
    allowed_roles text[]
)
returns boolean
language sql
stable
security definer
set search_path = public, pg_temp
as $function$
    select exists (
        select 1
        from public.beekeep_workspace_members m
        where m.workspace_id = target_workspace_id
          and m.user_id = auth.uid()
          and m.role = any(allowed_roles)
    );
$function$;

revoke all on function public.is_beekeep_workspace_member(uuid) from public, anon;
revoke all on function public.has_beekeep_workspace_role(uuid, text[]) from public, anon;
grant execute on function public.is_beekeep_workspace_member(uuid) to authenticated;
grant execute on function public.has_beekeep_workspace_role(uuid, text[]) to authenticated;

-- Create the initial personal workspace and Owner membership for users who sign up after
-- this migration. This trigger runs server-side as its owner; the Android client cannot
-- create or promote workspace memberships directly.
create or replace function public.create_beekeep_personal_workspace_for_user()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    personal_workspace_id uuid;
    preferred_name text;
begin
    preferred_name := coalesce(
        nullif(trim(new.raw_user_meta_data ->> 'workspace_name'), ''),
        'My BeeKeep Workspace'
    );

    insert into public.beekeep_workspaces (name, owner_user_id, is_personal)
    values (preferred_name, new.id, true)
    on conflict (owner_user_id) where is_personal = true do nothing;

    select w.id into personal_workspace_id
    from public.beekeep_workspaces w
    where w.owner_user_id = new.id and w.is_personal = true;

    if personal_workspace_id is null then
        raise exception 'Could not initialize BeeKeep personal workspace for user %', new.id;
    end if;

    insert into public.beekeep_workspace_members (workspace_id, user_id, role)
    values (personal_workspace_id, new.id, 'owner')
    on conflict (workspace_id, user_id)
    do update set role = 'owner';

    return new;
end;
$function$;

revoke all on function public.create_beekeep_personal_workspace_for_user() from public, anon, authenticated;

-- Backfill all existing accounts. Existing users each receive their own workspace, so no
-- pre-existing account's records are combined with or exposed to another account.
insert into public.beekeep_workspaces (name, owner_user_id, is_personal)
select
    coalesce(
        nullif(trim(u.raw_user_meta_data ->> 'workspace_name'), ''),
        'My BeeKeep Workspace'
    ),
    u.id,
    true
from auth.users u
on conflict (owner_user_id) where is_personal = true do nothing;

insert into public.beekeep_workspace_members (workspace_id, user_id, role)
select w.id, w.owner_user_id, 'owner'
from public.beekeep_workspaces w
where w.is_personal = true
on conflict (workspace_id, user_id)
do update set role = 'owner';

drop trigger if exists beekeep_create_personal_workspace_after_signup on auth.users;
create trigger beekeep_create_personal_workspace_after_signup
after insert on auth.users
for each row execute function public.create_beekeep_personal_workspace_for_user();

-- Keep the legacy beekeep_documents table shape unchanged. Older app versions decode every
-- returned document field, so workspace scope is stored in a side table rather than adding a
-- workspace_id column to beekeep_documents. This allows old builds to keep syncing privately.
create table if not exists public.beekeep_document_workspaces (
    user_id uuid not null references auth.users(id) on delete cascade,
    entity_type text not null,
    entity_id bigint not null,
    workspace_id uuid not null references public.beekeep_workspaces(id) on delete cascade,
    primary key (user_id, entity_type, entity_id),
    constraint beekeep_document_workspaces_document_fkey
        foreign key (user_id, entity_type, entity_id)
        references public.beekeep_documents(user_id, entity_type, entity_id)
        on delete cascade
);

create index if not exists beekeep_document_workspaces_workspace_idx
    on public.beekeep_document_workspaces (workspace_id, entity_type, entity_id);

-- Backfill each document to the private workspace owned by its existing user.
insert into public.beekeep_document_workspaces (user_id, entity_type, entity_id, workspace_id)
select d.user_id, d.entity_type, d.entity_id, w.id
from public.beekeep_documents d
join public.beekeep_workspaces w
  on w.owner_user_id = d.user_id
 and w.is_personal = true
on conflict (user_id, entity_type, entity_id)
do update set workspace_id = excluded.workspace_id;

-- Keep the mapping current as older and current app versions continue to write user-scoped
-- documents. This trigger intentionally maps writes only to the signed-in user's personal
-- workspace; Stage 3 will replace this behavior for explicitly selected shared workspaces.
create or replace function public.record_beekeep_document_personal_workspace()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    personal_workspace_id uuid;
begin
    if auth.uid() is null or new.user_id is distinct from auth.uid() then
        raise exception 'BeeKeep documents can only be written for the signed-in user';
    end if;

    select w.id into personal_workspace_id
    from public.beekeep_workspaces w
    where w.owner_user_id = new.user_id
      and w.is_personal = true;

    if personal_workspace_id is null then
        raise exception 'BeeKeep personal workspace is missing; sign in again or contact support';
    end if;

    insert into public.beekeep_document_workspaces (user_id, entity_type, entity_id, workspace_id)
    values (new.user_id, new.entity_type, new.entity_id, personal_workspace_id)
    on conflict (user_id, entity_type, entity_id)
    do update set workspace_id = excluded.workspace_id;

    return new;
end;
$function$;

drop trigger if exists beekeep_documents_assign_personal_workspace on public.beekeep_documents;
create trigger beekeep_documents_assign_personal_workspace
after insert or update on public.beekeep_documents
for each row execute function public.record_beekeep_document_personal_workspace();

revoke all on function public.record_beekeep_document_personal_workspace() from public, anon, authenticated;

alter table public.beekeep_document_workspaces enable row level security;

drop policy if exists "BeeKeep users can read their document workspace mappings" on public.beekeep_document_workspaces;
create policy "BeeKeep users can read their document workspace mappings"
on public.beekeep_document_workspaces
for select to authenticated
using (auth.uid() = user_id);

-- Mapping writes are trigger-only. Keep the join metadata private until the shared-sync stage
-- adds membership-based reads and a server-validated write path.
revoke all on table public.beekeep_document_workspaces from anon, authenticated;
grant select on table public.beekeep_document_workspaces to authenticated;

alter table public.beekeep_workspaces enable row level security;
alter table public.beekeep_workspace_members enable row level security;

drop policy if exists "BeeKeep workspace members can read their workspaces" on public.beekeep_workspaces;
create policy "BeeKeep workspace members can read their workspaces"
on public.beekeep_workspaces
for select to authenticated
using (public.is_beekeep_workspace_member(id));

drop policy if exists "BeeKeep workspace members can read workspace membership" on public.beekeep_workspace_members;
create policy "BeeKeep workspace members can read workspace membership"
on public.beekeep_workspace_members
for select to authenticated
using (public.is_beekeep_workspace_member(workspace_id));

-- Stage 1 clients can read workspace metadata/membership, but cannot grant themselves
-- access, change roles, transfer ownership or delete a workspace. Those changes will be
-- routed through audited, role-checked server functions in the invitation stage.
revoke all on table public.beekeep_workspaces from anon, authenticated;
revoke all on table public.beekeep_workspace_members from anon, authenticated;
grant select on table public.beekeep_workspaces to authenticated;
grant select on table public.beekeep_workspace_members to authenticated;

commit;
