-- Return a membership-scoped workspace owner entitlement in the same row shape
-- as the current-account entitlement RPC, so clients can lock expired team workspaces
-- for both owners and invited teammates without accepting client-provided plan data.
begin;

create or replace function public.beekeep_workspace_owner_commercial_entitlement(
    target_workspace_id uuid
)
returns table(active boolean)
language sql
stable
security definer
set search_path = ''
as $function$
    select public.beekeep_workspace_owner_has_active_commercial(target_workspace_id);
$function$;

revoke all on function public.beekeep_workspace_owner_commercial_entitlement(uuid)
    from public, anon;
grant execute on function public.beekeep_workspace_owner_commercial_entitlement(uuid)
    to authenticated;

commit;
