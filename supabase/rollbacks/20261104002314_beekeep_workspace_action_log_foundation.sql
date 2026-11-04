-- Rollback for 20261104002314_beekeep_workspace_action_log_foundation.sql.
-- Removes only the Actions feature schema and its scheduled cleanup job.
-- It never touches hive, apiary, inspection, feeding, treatment, harvest, or workspace records.
-- pg_cron itself is intentionally left installed because other project jobs may use it.

begin;

do $scheduler$
begin
    if exists (select 1 from pg_catalog.pg_extension where extname = 'pg_cron') then
        execute $unschedule$
            select cron.unschedule(jobid)
            from cron.job
            where jobname = 'beekeep-workspace-action-log-expiry'
        $unschedule$;
    end if;
end;
$scheduler$;

drop trigger if exists beekeep_workspace_action_log_seed_settings
    on public.beekeep_workspaces;

drop function if exists public.beekeep_workspace_action_log_seed_settings();
drop function if exists public.beekeep_workspace_action_log_expire();
drop function if exists public.beekeep_workspace_action_log_delete_all(uuid);
drop function if exists public.beekeep_workspace_action_log_delete(uuid);
drop function if exists public.beekeep_workspace_action_log_set_retention(uuid, text);

drop policy if exists "Only the team owner can read active workspace actions"
    on public.beekeep_workspace_action_log;
drop policy if exists "Workspace action-log retention is owner-only"
    on public.beekeep_workspace_action_log_settings;

drop table if exists public.beekeep_workspace_action_log;
drop table if exists public.beekeep_workspace_action_log_settings;

commit;
