-- Stage 3B: index the shared-record editor foreign key and avoid per-row auth.uid() calls.
begin;

create index if not exists beekeep_workspace_documents_updated_by_idx
    on public.beekeep_workspace_documents (updated_by);

drop policy if exists "BeeKeep writable members can create shared records"
    on public.beekeep_workspace_documents;
create policy "BeeKeep writable members can create shared records"
on public.beekeep_workspace_documents
for insert to authenticated
with check (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
    and updated_by = (select auth.uid())
    and exists (
        select 1 from public.beekeep_workspaces w
        where w.id = workspace_id and w.is_personal = false
    )
);

drop policy if exists "BeeKeep writable members can update shared records"
    on public.beekeep_workspace_documents;
create policy "BeeKeep writable members can update shared records"
on public.beekeep_workspace_documents
for update to authenticated
using (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
)
with check (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
    and updated_by = (select auth.uid())
    and exists (
        select 1 from public.beekeep_workspaces w
        where w.id = workspace_id and w.is_personal = false
    )
);

commit;
