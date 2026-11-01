-- Add a backward-compatible server-side change ledger for incremental BeeKeep sync.
-- The existing document tables and payloads remain unchanged for older app versions.
begin;

-- Keep the two source tables stable while seeding the ledger and installing its triggers.
lock table public.beekeep_documents in share row exclusive mode;
lock table public.beekeep_workspace_documents in share row exclusive mode;

create table if not exists public.beekeep_personal_sync_state (
    user_id uuid primary key references auth.users(id) on delete cascade,
    current_version bigint not null default 0 check (current_version >= 0)
);

create table if not exists public.beekeep_personal_sync_versions (
    user_id uuid not null references auth.users(id) on delete cascade,
    entity_type text not null,
    entity_id bigint not null,
    sync_version bigint not null check (sync_version > 0),
    primary key (user_id, entity_type, entity_id)
);

create index if not exists beekeep_personal_sync_versions_page_idx
    on public.beekeep_personal_sync_versions (user_id, sync_version);

create table if not exists public.beekeep_workspace_sync_state (
    workspace_id uuid primary key references public.beekeep_workspaces(id) on delete cascade,
    current_version bigint not null default 0 check (current_version >= 0)
);

create table if not exists public.beekeep_workspace_sync_versions (
    workspace_id uuid not null references public.beekeep_workspaces(id) on delete cascade,
    entity_type text not null,
    entity_id bigint not null,
    sync_version bigint not null check (sync_version > 0),
    primary key (workspace_id, entity_type, entity_id)
);

create index if not exists beekeep_workspace_sync_versions_page_idx
    on public.beekeep_workspace_sync_versions (workspace_id, sync_version);

-- Give every existing document a baseline version. New app installs start at the
-- current head and therefore perform a complete first pull; later pulls are deltas.
with ranked as (
    select
        d.user_id,
        d.entity_type,
        d.entity_id,
        row_number() over (
            partition by d.user_id
            order by d.entity_type, d.entity_id
        )::bigint as sync_version
    from public.beekeep_documents d
)
insert into public.beekeep_personal_sync_versions (
    user_id, entity_type, entity_id, sync_version
)
select user_id, entity_type, entity_id, sync_version
from ranked
on conflict (user_id, entity_type, entity_id) do nothing;

insert into public.beekeep_personal_sync_state (user_id, current_version)
select user_id, max(sync_version)
from public.beekeep_personal_sync_versions
group by user_id
on conflict (user_id) do update
set current_version = greatest(
    public.beekeep_personal_sync_state.current_version,
    excluded.current_version
);

with ranked as (
    select
        d.workspace_id,
        d.entity_type,
        d.entity_id,
        row_number() over (
            partition by d.workspace_id
            order by d.entity_type, d.entity_id
        )::bigint as sync_version
    from public.beekeep_workspace_documents d
)
insert into public.beekeep_workspace_sync_versions (
    workspace_id, entity_type, entity_id, sync_version
)
select workspace_id, entity_type, entity_id, sync_version
from ranked
on conflict (workspace_id, entity_type, entity_id) do nothing;

insert into public.beekeep_workspace_sync_state (workspace_id, current_version)
select workspace_id, max(sync_version)
from public.beekeep_workspace_sync_versions
group by workspace_id
on conflict (workspace_id) do update
set current_version = greatest(
    public.beekeep_workspace_sync_state.current_version,
    excluded.current_version
);

alter table public.beekeep_personal_sync_state enable row level security;
alter table public.beekeep_personal_sync_versions enable row level security;
alter table public.beekeep_workspace_sync_state enable row level security;
alter table public.beekeep_workspace_sync_versions enable row level security;

revoke all on table
    public.beekeep_personal_sync_state,
    public.beekeep_personal_sync_versions,
    public.beekeep_workspace_sync_state,
    public.beekeep_workspace_sync_versions
from public, anon, authenticated;

grant select on table
    public.beekeep_personal_sync_state,
    public.beekeep_personal_sync_versions,
    public.beekeep_workspace_sync_state,
    public.beekeep_workspace_sync_versions
to authenticated;

drop policy if exists "BeeKeep users can read personal sync state"
    on public.beekeep_personal_sync_state;
create policy "BeeKeep users can read personal sync state"
on public.beekeep_personal_sync_state
for select to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "BeeKeep users can read personal sync versions"
    on public.beekeep_personal_sync_versions;
create policy "BeeKeep users can read personal sync versions"
on public.beekeep_personal_sync_versions
for select to authenticated
using ((select auth.uid()) = user_id);

drop policy if exists "BeeKeep workspace members can read sync state"
    on public.beekeep_workspace_sync_state;
create policy "BeeKeep workspace members can read sync state"
on public.beekeep_workspace_sync_state
for select to authenticated
using (public.is_beekeep_workspace_member(workspace_id));

drop policy if exists "BeeKeep workspace members can read sync versions"
    on public.beekeep_workspace_sync_versions;
create policy "BeeKeep workspace members can read sync versions"
on public.beekeep_workspace_sync_versions
for select to authenticated
using (public.is_beekeep_workspace_member(workspace_id));

-- The per-scope state row is updated in the same transaction as the document write.
-- Postgres row locking serializes writers for that user/workspace and guarantees that
-- a later committed write always receives a greater sync version.
create or replace function public.beekeep_record_document_sync_version()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    next_version bigint;
begin
    if tg_table_name = 'beekeep_documents' then
        insert into public.beekeep_personal_sync_state as current_state (
            user_id, current_version
        )
        values (new.user_id, 1)
        on conflict (user_id) do update
            set current_version = current_state.current_version + 1
        returning current_version into next_version;

        insert into public.beekeep_personal_sync_versions (
            user_id, entity_type, entity_id, sync_version
        )
        values (new.user_id, new.entity_type, new.entity_id, next_version)
        on conflict (user_id, entity_type, entity_id) do update
            set sync_version = excluded.sync_version;

    elsif tg_table_name = 'beekeep_workspace_documents' then
        insert into public.beekeep_workspace_sync_state as current_state (
            workspace_id, current_version
        )
        values (new.workspace_id, 1)
        on conflict (workspace_id) do update
            set current_version = current_state.current_version + 1
        returning current_version into next_version;

        insert into public.beekeep_workspace_sync_versions (
            workspace_id, entity_type, entity_id, sync_version
        )
        values (new.workspace_id, new.entity_type, new.entity_id, next_version)
        on conflict (workspace_id, entity_type, entity_id) do update
            set sync_version = excluded.sync_version;
    else
        raise exception 'Unsupported BeeKeep sync source table: %', tg_table_name;
    end if;

    return new;
end;
$function$;

revoke all on function public.beekeep_record_document_sync_version()
    from public, anon, authenticated;

drop trigger if exists beekeep_documents_record_sync_version
    on public.beekeep_documents;
create trigger beekeep_documents_record_sync_version
after insert or update on public.beekeep_documents
for each row execute function public.beekeep_record_document_sync_version();

drop trigger if exists beekeep_workspace_documents_record_sync_version
    on public.beekeep_workspace_documents;
create trigger beekeep_workspace_documents_record_sync_version
after insert or update on public.beekeep_workspace_documents
for each row execute function public.beekeep_record_document_sync_version();

create or replace function public.beekeep_personal_sync_head()
returns table(head_version bigint)
language sql
stable
security invoker
set search_path = ''
as $function$
    select coalesce((
        select s.current_version
        from public.beekeep_personal_sync_state s
        where s.user_id = (select auth.uid())
    ), 0)::bigint;
$function$;

create or replace function public.beekeep_workspace_sync_head(target_workspace_id uuid)
returns table(head_version bigint)
language sql
stable
security invoker
set search_path = ''
as $function$
    select coalesce((
        select s.current_version
        from public.beekeep_workspace_sync_state s
        where s.workspace_id = target_workspace_id
    ), 0)::bigint;
$function$;

create or replace function public.beekeep_personal_sync_changes(
    after_version bigint,
    through_version bigint,
    requested_page_size integer default 500
)
returns table(
    user_id uuid,
    entity_type text,
    entity_id bigint,
    payload text,
    updated_at bigint,
    deleted boolean,
    device_id text,
    sync_version bigint
)
language sql
stable
security invoker
set search_path = ''
as $function$
    select
        d.user_id,
        d.entity_type,
        d.entity_id,
        d.payload,
        d.updated_at,
        d.deleted,
        d.device_id,
        v.sync_version
    from public.beekeep_personal_sync_versions v
    join public.beekeep_documents d
      on d.user_id = v.user_id
     and d.entity_type = v.entity_type
     and d.entity_id = v.entity_id
    where v.user_id = (select auth.uid())
      and v.sync_version > greatest(coalesce(after_version, 0), 0)
      and v.sync_version <= greatest(coalesce(through_version, 0), 0)
    order by v.sync_version asc
    limit least(greatest(coalesce(requested_page_size, 500), 1), 500);
$function$;

create or replace function public.beekeep_workspace_sync_changes(
    target_workspace_id uuid,
    after_version bigint,
    through_version bigint,
    requested_page_size integer default 500
)
returns table(
    workspace_id uuid,
    entity_type text,
    entity_id bigint,
    payload text,
    updated_at bigint,
    deleted boolean,
    device_id text,
    updated_by uuid,
    sync_version bigint
)
language sql
stable
security invoker
set search_path = ''
as $function$
    select
        d.workspace_id,
        d.entity_type,
        d.entity_id,
        d.payload,
        d.updated_at,
        d.deleted,
        d.device_id,
        d.updated_by,
        v.sync_version
    from public.beekeep_workspace_sync_versions v
    join public.beekeep_workspace_documents d
      on d.workspace_id = v.workspace_id
     and d.entity_type = v.entity_type
     and d.entity_id = v.entity_id
    where v.workspace_id = target_workspace_id
      and v.sync_version > greatest(coalesce(after_version, 0), 0)
      and v.sync_version <= greatest(coalesce(through_version, 0), 0)
    order by v.sync_version asc
    limit least(greatest(coalesce(requested_page_size, 500), 1), 500);
$function$;

revoke all on function public.beekeep_personal_sync_head()
    from public, anon;
revoke all on function public.beekeep_workspace_sync_head(uuid)
    from public, anon;
revoke all on function public.beekeep_personal_sync_changes(bigint, bigint, integer)
    from public, anon;
revoke all on function public.beekeep_workspace_sync_changes(uuid, bigint, bigint, integer)
    from public, anon;

grant execute on function public.beekeep_personal_sync_head() to authenticated;
grant execute on function public.beekeep_workspace_sync_head(uuid) to authenticated;
grant execute on function public.beekeep_personal_sync_changes(bigint, bigint, integer) to authenticated;
grant execute on function public.beekeep_workspace_sync_changes(uuid, bigint, bigint, integer) to authenticated;

commit;
