-- Membership-scoped workspace quota lookup for the selected shared dataset.
-- Returns the owner's exact resolved limits (including approved hive overrides), not
-- the signed-in teammate's personal plan. Non-members receive no row.
begin;

create or replace function public.beekeep_workspace_owner_quota_entitlement(
    target_workspace_id uuid
)
returns table (
    active boolean,
    effective_hive_limit integer,
    effective_apiary_limit integer,
    member_role text
)
language sql
stable
security definer
set search_path = ''
as $function$
    select
        public.beekeep_user_has_active_commercial(w.owner_user_id) as active,
        public.beekeep_resolved_hive_limit(w.owner_user_id) as effective_hive_limit,
        public.beekeep_resolved_apiary_limit(w.owner_user_id) as effective_apiary_limit,
        m.role as member_role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m
      on m.workspace_id = w.id
     and m.user_id = auth.uid()
    where w.id = target_workspace_id
      and w.is_personal = false;
$function$;

revoke all on function public.beekeep_workspace_owner_quota_entitlement(uuid)
    from public, anon;
grant execute on function public.beekeep_workspace_owner_quota_entitlement(uuid)
    to authenticated;

commit;
