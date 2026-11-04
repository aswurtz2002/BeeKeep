-- Rollback for automatic workspace Actions logging.
-- Removes only audit triggers/RPC metadata columns and replay function.
-- Existing workspace business documents and Workspace Action log entries are preserved.

begin;

drop trigger if exists beekeep_workspace_action_log_after_update
    on public.beekeep_workspace_documents;
drop trigger if exists beekeep_workspace_action_log_after_insert
    on public.beekeep_workspace_documents;
drop trigger if exists beekeep_workspace_document_action_stamp
    on public.beekeep_workspace_documents;

drop function if exists public.beekeep_workspace_action_log_after_update();
drop function if exists public.beekeep_workspace_action_log_after_insert();
drop function if exists public.beekeep_workspace_action_log_description(uuid, text, bigint, jsonb, text);
drop function if exists public.beekeep_workspace_document_action_stamp();
drop function if exists public.beekeep_replay_workspace_action_changes(uuid, jsonb);

alter table public.beekeep_workspace_documents
    drop column if exists client_action_id,
    drop column if exists action_at;

commit;
