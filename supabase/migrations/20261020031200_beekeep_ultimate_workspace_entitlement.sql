-- Enforce Ultimate ownership for shared team workspaces.
--
-- Trusted entitlement contract:
--   auth.users.raw_app_meta_data.beekeep_plan = ultimate | ultimate_monthly | ultimate_yearly
--   auth.users.raw_app_meta_data.beekeep_subscription_expires_at = an ISO-8601 timestamp
-- Both fields must be written by trusted server/admin code (for example, verified billing
-- notifications). Never accept these values from raw_user_meta_data or a mobile RPC argument.
--
-- Invited members do not need Ultimate. They can read and write shared records while the
-- workspace owner has an active Ultimate entitlement. If the owner's entitlement lapses,
-- existing data remains readable but shared-record writes and new invitations are blocked.
begin;

create or replace function public.beekeep_user_has_active_ultimate(target_user_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    metadata jsonb;
    normalized_plan text;
    expiry_text text;
    expiry_value timestamptz;
begin
    if target_user_id is null then
        return false;
    end if;

    select coalesce(u.raw_app_meta_data, '{}'::jsonb)
    into metadata
    from auth.users u
    where u.id = target_user_id;

    if not found then
        return false;
    end if;

    normalized_plan := regexp_replace(
        lower(coalesce(metadata ->> 'beekeep_plan', '')),
        '[^a-z]',
        '',
        'g'
    );
    if normalized_plan not in ('ultimate', 'ultimatemonthly', 'ultimateyearly') then
        return false;
    end if;

    -- Monthly/yearly Ultimate access must always have an expiry. Missing or malformed
    -- timestamps fail closed instead of accidentally granting permanent access.
    expiry_text := nullif(trim(metadata ->> 'beekeep_subscription_expires_at'), '');
    if expiry_text is null then
        return false;
    end if;

    begin
        expiry_value := expiry_text::timestamptz;
    exception when others then
        return false;
    end;

    return expiry_value > now();
end;
$function$;

-- This helper only reveals the entitlement of a workspace's owner to a current member
-- of that shared workspace. Non-members cannot use it to probe arbitrary user accounts.
create or replace function public.beekeep_workspace_owner_has_active_ultimate(target_workspace_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select coalesce((
        select public.beekeep_user_has_active_ultimate(w.owner_user_id)
        from public.beekeep_workspaces w
        where w.id = target_workspace_id
          and w.is_personal = false
          and exists (
              select 1
              from public.beekeep_workspace_members m
              where m.workspace_id = w.id
                and m.user_id = auth.uid()
          )
    ), false);
$function$;

create or replace function public.beekeep_my_active_ultimate_entitlement()
returns table(active boolean)
language sql
stable
security definer
set search_path = ''
as $function$
    select public.beekeep_user_has_active_ultimate(auth.uid());
$function$;

revoke all on function public.beekeep_user_has_active_ultimate(uuid) from public, anon, authenticated;
revoke all on function public.beekeep_workspace_owner_has_active_ultimate(uuid) from public, anon;
revoke all on function public.beekeep_my_active_ultimate_entitlement() from public, anon;
grant execute on function public.beekeep_workspace_owner_has_active_ultimate(uuid) to authenticated;
grant execute on function public.beekeep_my_active_ultimate_entitlement() to authenticated;

create or replace function public.beekeep_enforce_ultimate_for_workspace_owner()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if new.is_personal = false
       and not public.beekeep_user_has_active_ultimate(new.owner_user_id) then
        raise exception 'An active BeeKeep Ultimate subscription is required to create a team workspace.'
            using errcode = '42501';
    end if;
    return new;
end;
$function$;

drop trigger if exists beekeep_ultimate_workspace_owner_guard on public.beekeep_workspaces;
create trigger beekeep_ultimate_workspace_owner_guard
before insert on public.beekeep_workspaces
for each row
when (new.is_personal = false)
execute function public.beekeep_enforce_ultimate_for_workspace_owner();

create or replace function public.beekeep_enforce_ultimate_for_workspace_invitation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if new.status = 'pending'
       and not public.beekeep_workspace_owner_has_active_ultimate(new.workspace_id) then
        raise exception 'The team workspace owner must have an active BeeKeep Ultimate subscription to invite teammates.'
            using errcode = '42501';
    end if;
    return new;
end;
$function$;

drop trigger if exists beekeep_ultimate_workspace_invitation_guard on public.beekeep_workspace_invitations;
create trigger beekeep_ultimate_workspace_invitation_guard
before insert on public.beekeep_workspace_invitations
for each row
execute function public.beekeep_enforce_ultimate_for_workspace_invitation();

revoke all on function public.beekeep_enforce_ultimate_for_workspace_owner() from public, anon, authenticated;
revoke all on function public.beekeep_enforce_ultimate_for_workspace_invitation() from public, anon, authenticated;

-- Enforce the workspace owner's entitlement at the database boundary for writes. Reads are
-- intentionally retained after expiry so the team can still view its existing data.
drop policy if exists "BeeKeep writable members can create shared records" on public.beekeep_workspace_documents;
create policy "BeeKeep writable members can create shared records"
on public.beekeep_workspace_documents
for insert to authenticated
with check (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
    and public.beekeep_workspace_owner_has_active_ultimate(workspace_id)
    and updated_by = auth.uid()
    and exists (
        select 1 from public.beekeep_workspaces w
        where w.id = workspace_id and w.is_personal = false
    )
);

drop policy if exists "BeeKeep writable members can update shared records" on public.beekeep_workspace_documents;
create policy "BeeKeep writable members can update shared records"
on public.beekeep_workspace_documents
for update to authenticated
using (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
    and public.beekeep_workspace_owner_has_active_ultimate(workspace_id)
)
with check (
    public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin', 'member'])
    and public.beekeep_workspace_owner_has_active_ultimate(workspace_id)
    and updated_by = auth.uid()
    and exists (
        select 1 from public.beekeep_workspaces w
        where w.id = workspace_id and w.is_personal = false
    )
);

-- This operation is SECURITY DEFINER and therefore needs its own entitlement check rather
-- than relying on the shared-document RLS policies.
create or replace function public.beekeep_copy_personal_documents_to_workspace(target_workspace_id uuid)
returns table(copied_count integer)
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := auth.uid();
    inserted_count integer := 0;
begin
    if caller_id is null then
        raise exception 'Sign in before copying personal records to a team workspace.'
            using errcode = '42501';
    end if;

    if target_workspace_id is null
       or not exists (
            select 1
            from public.beekeep_workspaces w
            where w.id = target_workspace_id
              and w.is_personal = false
              and public.has_beekeep_workspace_role(
                    w.id,
                    array['owner', 'admin']::text[]
                  )
       ) then
        raise exception 'Only a workspace owner or admin can copy personal records into this team.'
            using errcode = '42501';
    end if;

    if not public.beekeep_workspace_owner_has_active_ultimate(target_workspace_id) then
        raise exception 'The team workspace owner must have an active BeeKeep Ultimate subscription to copy records.'
            using errcode = '42501';
    end if;

    if exists (
        select 1
        from public.beekeep_documents d
        join public.beekeep_workspace_documents shared
          on shared.workspace_id = target_workspace_id
         and shared.entity_type = d.entity_type
         and shared.entity_id = d.entity_id
        where d.user_id = caller_id
    ) then
        raise exception 'Some personal record IDs already exist in this team workspace. No personal records were copied.'
            using errcode = '23505';
    end if;

    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
    )
    select
        target_workspace_id, d.entity_type, d.entity_id, d.payload, d.updated_at, d.deleted, d.device_id, caller_id
    from public.beekeep_documents d
    where d.user_id = caller_id;

    get diagnostics inserted_count = row_count;
    return query select inserted_count;
end;
$function$;

commit;
