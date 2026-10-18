-- Stage 3A: workspace-scoped shared records.
-- This table is deliberately separate from beekeep_documents so older app versions
-- continue to sync only a user's private/personal records.
begin;

create table if not exists public.beekeep_workspace_documents (
    workspace_id uuid not null references public.beekeep_workspaces(id) on delete cascade,
    entity_type text not null check (length(trim(entity_type)) > 0),
    entity_id bigint not null,
    payload text not null,
    updated_at bigint not null,
    deleted boolean not null default false,
    device_id text not null default 'android',
    updated_by uuid references auth.users(id) on delete set null,
    primary key (workspace_id, entity_type, entity_id)
);

create index if not exists beekeep_workspace_documents_updated_at_idx
    on public.beekeep_workspace_documents (workspace_id, updated_at);

alter table public.beekeep_workspace_documents enable row level security;
alter table public.beekeep_workspace_documents replica identity full;
revoke all on table public.beekeep_workspace_documents from anon, authenticated;
grant select, insert, update on table public.beekeep_workspace_documents to authenticated;

drop policy if exists "BeeKeep workspace members can read shared records" on public.beekeep_workspace_documents;
create policy "BeeKeep workspace members can read shared records"
on public.beekeep_workspace_documents
for select to authenticated
using (public.is_beekeep_workspace_member(workspace_id));

drop policy if exists "BeeKeep writable members can create shared records" on public.beekeep_workspace_documents;
create policy "BeeKeep writable members can create shared records"
on public.beekeep_workspace_documents
for insert to authenticated
with check (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
    and updated_by = auth.uid()
    and exists (
        select 1 from public.beekeep_workspaces w
        where w.id = workspace_id and w.is_personal = false
    )
);

drop policy if exists "BeeKeep writable members can update shared records" on public.beekeep_workspace_documents;
create policy "BeeKeep writable members can update shared records"
on public.beekeep_workspace_documents
for update to authenticated
using (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
)
with check (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
    and updated_by = auth.uid()
    and exists (
        select 1 from public.beekeep_workspaces w
        where w.id = workspace_id and w.is_personal = false
    )
);

-- Prevent a client from re-keying an existing document into another workspace or entity.
create or replace function public.beekeep_guard_workspace_document_key()
returns trigger
language plpgsql
set search_path = public, pg_temp
as $function$
begin
    if new.workspace_id is distinct from old.workspace_id
       or new.entity_type is distinct from old.entity_type
       or new.entity_id is distinct from old.entity_id then
        raise exception 'Shared record identity cannot be changed';
    end if;
    return new;
end;
$function$;

drop trigger if exists beekeep_workspace_document_key_guard on public.beekeep_workspace_documents;
create trigger beekeep_workspace_document_key_guard
before update on public.beekeep_workspace_documents
for each row execute function public.beekeep_guard_workspace_document_key();

revoke all on function public.beekeep_guard_workspace_document_key() from public, anon, authenticated;

do $publication$
begin
    if exists (select 1 from pg_publication where pubname = 'supabase_realtime')
       and not exists (
            select 1 from pg_publication_tables
            where pubname = 'supabase_realtime'
              and schemaname = 'public'
              and tablename = 'beekeep_workspace_documents'
       ) then
        alter publication supabase_realtime add table public.beekeep_workspace_documents;
    end if;
end;
$publication$;

commit;
