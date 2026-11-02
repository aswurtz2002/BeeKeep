-- Keep the Commercial owner-entitlement predicate internal. Existing authenticated
-- clients retain the compatibility RPC beekeep_workspace_owner_has_active_ultimate();
-- policies and SECURITY DEFINER functions call this helper as its owner.
revoke all on function public.beekeep_workspace_owner_has_active_commercial(uuid)
    from public, anon, authenticated;
