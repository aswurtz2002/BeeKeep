-- Prevent delayed/offline clients from moving cloud documents backwards.
-- Equal timestamps use server commit order; permanent record tombstones cannot be resurrected.
begin;

create or replace function public.beekeep_guard_document_conflict()
returns trigger
language plpgsql
set search_path = ''
as $function$
begin
    if tg_op = 'UPDATE' then
        -- Draft document IDs intentionally correspond to a hive, so a later edit
        -- may follow a draft-clear tombstone. Other deleted entity IDs are permanent.
        if old.deleted
           and not new.deleted
           and new.entity_type not in ('inspection_note_draft', 'inspection_form_draft') then
            return null;
        end if;

        -- Returning NULL skips the row update and its AFTER sync-version trigger.
        if new.updated_at < old.updated_at then
            return null;
        end if;
    end if;

    return new;
end;
$function$;

revoke all on function public.beekeep_guard_document_conflict()
    from public, anon, authenticated;

drop trigger if exists beekeep_documents_conflict_guard
    on public.beekeep_documents;
create trigger beekeep_documents_conflict_guard
before update on public.beekeep_documents
for each row execute function public.beekeep_guard_document_conflict();

drop trigger if exists beekeep_workspace_documents_conflict_guard
    on public.beekeep_workspace_documents;
create trigger beekeep_workspace_documents_conflict_guard
before update on public.beekeep_workspace_documents
for each row execute function public.beekeep_guard_document_conflict();

commit;
