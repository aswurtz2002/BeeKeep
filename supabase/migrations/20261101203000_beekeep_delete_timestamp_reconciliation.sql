-- Explicit user deletions must not be lost because the deleting device clock is behind.
-- The server serializes the tombstone timestamp against the current row, even if another
-- device updates the same entity between the client's conflict lookup and its upsert.
create or replace function public.beekeep_guard_document_conflict()
returns trigger
language plpgsql
set search_path = ''
as $function$
begin
    if tg_op = 'UPDATE' then
        -- A permanent deletion is authoritative for this entity identity. Draft IDs
        -- intentionally correspond to a hive, so later draft edits may reuse that ID.
        if old.deleted
           and not new.deleted
           and new.entity_type not in ('inspection_note_draft', 'inspection_form_draft') then
            return null;
        end if;

        -- An explicit delete is a new user action, not a stale content edit. Make
        -- its timestamp strictly newer than the row currently protected by this lock.
        -- This also handles a competing update committed after client preflight.
        if new.deleted
           and not old.deleted
           and new.entity_type not in ('inspection_note_draft', 'inspection_form_draft') then
            new.updated_at := greatest(new.updated_at, old.updated_at + 1);
            return new;
        end if;

        -- Returning NULL skips the row update and its AFTER sync-version trigger.
        if new.updated_at < old.updated_at then
            return null;
        end if;
    end if;

    return new;
end;
$function$;
