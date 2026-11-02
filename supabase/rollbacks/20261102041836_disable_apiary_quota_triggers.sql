-- Emergency rollback: remove only the apiary quota triggers.
--
-- This does NOT revert Commercial entitlement resolution, 15/10,000 hive limits,
-- workspace read-only RLS, workspace management guards, or invitation acceptance checks.
-- Keep those security gates in place. Re-enable apiary limits with the forward migration:
-- 20261102041836_beekeep_commercial_entitlements_and_apiary_limits.sql
--
-- Run only if the apiary quota triggers cause a production incident; follow with a
-- corrective forward migration and restore the guards as soon as possible.
begin;

drop trigger if exists beekeep_enforce_personal_apiary_limit_after_insert
    on public.beekeep_documents;
drop trigger if exists beekeep_enforce_personal_apiary_limit_after_update
    on public.beekeep_documents;
drop trigger if exists beekeep_enforce_workspace_apiary_limit_after_insert
    on public.beekeep_workspace_documents;
drop trigger if exists beekeep_enforce_workspace_apiary_limit_after_update
    on public.beekeep_workspace_documents;

drop function if exists public.beekeep_enforce_personal_apiary_limit_after_insert();
drop function if exists public.beekeep_enforce_personal_apiary_limit_after_update();
drop function if exists public.beekeep_enforce_workspace_apiary_limit_after_insert();
drop function if exists public.beekeep_enforce_workspace_apiary_limit_after_update();
drop function if exists public.beekeep_resolved_apiary_limit(uuid);

commit;
