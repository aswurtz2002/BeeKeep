-- Idempotent hardening for the Stage 2 workspace manager functions.
-- Missing membership must fail closed: SQL NULL is not an authorization success.

create or replace function public.beekeep_cancel_workspace_invitation(target_invitation_id uuid)
returns boolean
language plpgsql security definer set search_path = public, pg_temp
as $function$
declare invite_row public.beekeep_workspace_invitations%rowtype; caller_role text;
begin
    if auth.uid() is null then raise exception 'Sign in to manage invitations'; end if;
    select i.* into invite_row from public.beekeep_workspace_invitations i where i.id = target_invitation_id for update;
    if invite_row.id is null then raise exception 'Invitation not found'; end if;
    select m.role into caller_role from public.beekeep_workspace_members m
    where m.workspace_id = invite_row.workspace_id and m.user_id = auth.uid();
    if caller_role is null or caller_role not in ('owner', 'admin') then
        raise exception 'Only owners and admins can cancel invitations';
    end if;
    if invite_row.status <> 'pending' then raise exception 'Invitation is no longer pending'; end if;
    if caller_role = 'admin' and invite_row.role = 'admin' then raise exception 'Only the owner can cancel an admin invitation'; end if;
    update public.beekeep_workspace_invitations set status = 'cancelled' where id = invite_row.id;
    return true;
end;
$function$;

create or replace function public.beekeep_update_workspace_member_role(target_workspace_id uuid, target_user_id uuid, new_role text)
returns boolean
language plpgsql security definer set search_path = public, pg_temp
as $function$
declare caller_role text; target_role text;
begin
    if auth.uid() is null then raise exception 'Sign in to manage members'; end if;
    if new_role is null or new_role not in ('admin', 'member', 'viewer') then raise exception 'Choose Admin, Member or Viewer'; end if;
    select m.role into caller_role from public.beekeep_workspace_members m
    join public.beekeep_workspaces w on w.id = m.workspace_id
    where m.workspace_id = target_workspace_id and m.user_id = auth.uid() and w.is_personal = false;
    if caller_role is null or caller_role not in ('owner', 'admin') then raise exception 'Only owners and admins can change member roles'; end if;
    select m.role into target_role from public.beekeep_workspace_members m
    where m.workspace_id = target_workspace_id and m.user_id = target_user_id;
    if target_role is null then raise exception 'Workspace member not found'; end if;
    if target_role = 'owner' then raise exception 'Workspace ownership cannot be changed here'; end if;
    if caller_role = 'admin' and (target_role = 'admin' or new_role = 'admin') then raise exception 'Only the owner can manage admin roles'; end if;
    if target_user_id = auth.uid() then raise exception 'You cannot change your own role'; end if;
    update public.beekeep_workspace_members set role = new_role where workspace_id = target_workspace_id and user_id = target_user_id;
    return true;
end;
$function$;

create or replace function public.beekeep_remove_workspace_member(target_workspace_id uuid, target_user_id uuid)
returns boolean
language plpgsql security definer set search_path = public, pg_temp
as $function$
declare caller_role text; target_role text;
begin
    if auth.uid() is null then raise exception 'Sign in to manage members'; end if;
    select m.role into caller_role from public.beekeep_workspace_members m
    join public.beekeep_workspaces w on w.id = m.workspace_id
    where m.workspace_id = target_workspace_id and m.user_id = auth.uid() and w.is_personal = false;
    if caller_role is null or caller_role not in ('owner', 'admin') then raise exception 'Only owners and admins can remove members'; end if;
    select m.role into target_role from public.beekeep_workspace_members m
    where m.workspace_id = target_workspace_id and m.user_id = target_user_id;
    if target_role is null then raise exception 'Workspace member not found'; end if;
    if target_role = 'owner' then raise exception 'The workspace owner cannot be removed'; end if;
    if target_user_id = auth.uid() then raise exception 'Use workspace transfer or owner tools before leaving your workspace'; end if;
    if caller_role = 'admin' and target_role = 'admin' then raise exception 'Only the owner can remove another admin'; end if;
    delete from public.beekeep_workspace_members where workspace_id = target_workspace_id and user_id = target_user_id;
    return true;
end;
$function$;

revoke all on function public.beekeep_cancel_workspace_invitation(uuid) from public, anon;
revoke all on function public.beekeep_update_workspace_member_role(uuid, uuid, text) from public, anon;
revoke all on function public.beekeep_remove_workspace_member(uuid, uuid) from public, anon;
grant execute on function public.beekeep_cancel_workspace_invitation(uuid) to authenticated;
grant execute on function public.beekeep_update_workspace_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.beekeep_remove_workspace_member(uuid, uuid) to authenticated;
