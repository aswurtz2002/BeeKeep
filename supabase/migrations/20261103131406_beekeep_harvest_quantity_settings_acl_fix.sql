-- Do not inherit broad default public-schema grants for action settings.
-- Authenticated clients need only SELECT/INSERT/UPDATE, still restricted by RLS.

begin;

revoke all on table public.beekeep_action_settings from anon, authenticated, public;
grant select, insert, update on table public.beekeep_action_settings to authenticated;

commit;
