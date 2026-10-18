-- Correct the invitation acceptance conflict target for the table-returning function.
-- The output column named workspace_id can shadow the same column name in ON CONFLICT.

create or replace function public.beekeep_accept_workspace_invitation(invite_token text)
returns table (workspace_id uuid, workspace_name text, workspace_role text)
language plpgsql security definer set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    caller_email text;
    invite_row public.beekeep_workspace_invitations%rowtype;
begin
    if caller_id is null then raise exception 'Sign in before accepting an invitation'; end if;
    if invite_token is null or length(invite_token) < 32 then raise exception 'Enter the invitation code'; end if;
    select lower(u.email) into caller_email from auth.users u where u.id = caller_id;
    if caller_email is null then raise exception 'Could not verify your account email'; end if;

    select i.* into invite_row
    from public.beekeep_workspace_invitations i
    where i.token_hash = encode(digest(invite_token, 'sha256'), 'hex')
    for update;

    if invite_row.id is null then raise exception 'Invitation code is invalid'; end if;
    if invite_row.status <> 'pending' then raise exception 'This invitation is no longer active'; end if;
    if invite_row.expires_at <= now() then
        update public.beekeep_workspace_invitations set status = 'expired' where id = invite_row.id;
        raise exception 'This invitation has expired';
    end if;
    if lower(invite_row.email) <> caller_email then
        raise exception 'Sign in with the email address the invitation was sent to';
    end if;

    insert into public.beekeep_workspace_members (workspace_id, user_id, role, invited_by)
    values (invite_row.workspace_id, caller_id, invite_row.role, invite_row.created_by)
    on conflict on constraint beekeep_workspace_members_pkey do nothing;

    update public.beekeep_workspace_invitations
    set status = 'accepted', accepted_by = caller_id
    where id = invite_row.id and status = 'pending';

    return query
    select w.id, w.name, m.role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m on m.workspace_id = w.id and m.user_id = caller_id
    where w.id = invite_row.workspace_id;
end;
$function$;

revoke all on function public.beekeep_accept_workspace_invitation(text) from public, anon;
grant execute on function public.beekeep_accept_workspace_invitation(text) to authenticated;
