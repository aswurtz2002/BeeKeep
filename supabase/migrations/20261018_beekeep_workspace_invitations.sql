-- Stage 2: secure workspace invitations and membership management.
-- Workspace membership is established only through role-checked database functions.
-- Shared apiary/hive replication remains a separate rollout stage.

begin;

create table if not exists public.beekeep_workspace_invitations (
    id uuid primary key default gen_random_uuid(),
    workspace_id uuid not null references public.beekeep_workspaces(id) on delete cascade,
    email text not null,
    role text not null check (role in ('admin', 'member', 'viewer')),
    token_hash text not null unique,
    status text not null default 'pending'
        check (status in ('pending', 'accepted', 'cancelled', 'expired')),
    created_by uuid not null references auth.users(id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null default (now() + interval '7 days'),
    accepted_by uuid references auth.users(id) on delete set null,
    constraint beekeep_workspace_invitation_email_not_blank check (length(trim(email)) > 3)
);

create index if not exists beekeep_workspace_invitations_workspace_status_idx
    on public.beekeep_workspace_invitations (workspace_id, status, created_at desc);

create unique index if not exists beekeep_workspace_invitation_one_pending_email_idx
    on public.beekeep_workspace_invitations (workspace_id, lower(email))
    where status = 'pending';

alter table public.beekeep_workspace_invitations enable row level security;
revoke all on table public.beekeep_workspace_invitations from anon, authenticated;
grant select on table public.beekeep_workspace_invitations to authenticated;

drop policy if exists "BeeKeep workspace managers can read invitations" on public.beekeep_workspace_invitations;
create policy "BeeKeep workspace managers can read invitations"
on public.beekeep_workspace_invitations
for select to authenticated
using (public.has_beekeep_workspace_role(workspace_id, array['owner', 'admin']));

create or replace function public.beekeep_list_workspaces()
returns table (
    id uuid,
    name text,
    owner_user_id uuid,
    is_personal boolean,
    member_role text,
    created_at timestamptz,
    updated_at timestamptz
)
language sql
stable
security definer
set search_path = public, pg_temp
as $function$
    select w.id, w.name, w.owner_user_id, w.is_personal, m.role, w.created_at, w.updated_at
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m on m.workspace_id = w.id
    where m.user_id = auth.uid()
    order by w.is_personal desc, w.created_at asc;
$function$;

create or replace function public.beekeep_create_workspace(workspace_name text)
returns table (
    id uuid,
    name text,
    owner_user_id uuid,
    is_personal boolean,
    member_role text,
    created_at timestamptz,
    updated_at timestamptz
)
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    cleaned_name text := trim(coalesce(workspace_name, ''));
    new_workspace_id uuid;
begin
    if caller_id is null then raise exception 'Sign in to create a workspace'; end if;
    if cleaned_name = '' then raise exception 'Enter a workspace name'; end if;
    if length(cleaned_name) > 80 then raise exception 'Workspace names must be 80 characters or fewer'; end if;

    insert into public.beekeep_workspaces (name, owner_user_id, is_personal)
    values (cleaned_name, caller_id, false)
    returning beekeep_workspaces.id into new_workspace_id;

    insert into public.beekeep_workspace_members (workspace_id, user_id, role)
    values (new_workspace_id, caller_id, 'owner');

    return query
    select w.id, w.name, w.owner_user_id, w.is_personal, m.role, w.created_at, w.updated_at
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m on m.workspace_id = w.id and m.user_id = caller_id
    where w.id = new_workspace_id;
end;
$function$;

create or replace function public.beekeep_list_workspace_members(target_workspace_id uuid)
returns table (
    workspace_id uuid,
    user_id uuid,
    email text,
    role text,
    joined_at timestamptz,
    is_current_user boolean
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $function$
begin
    if auth.uid() is null then raise exception 'Sign in to view workspace members'; end if;
    if not public.is_beekeep_workspace_member(target_workspace_id) then
        raise exception 'You are not a member of this workspace';
    end if;

    return query
    select m.workspace_id, m.user_id, coalesce(u.email, ''), m.role, m.joined_at, (m.user_id = auth.uid())
    from public.beekeep_workspace_members m
    left join auth.users u on u.id = m.user_id
    where m.workspace_id = target_workspace_id
    order by case m.role when 'owner' then 0 when 'admin' then 1 when 'member' then 2 else 3 end, m.joined_at;
end;
$function$;

create or replace function public.beekeep_list_workspace_invitations(target_workspace_id uuid)
returns table (
    id uuid,
    workspace_id uuid,
    email text,
    role text,
    status text,
    created_at timestamptz,
    expires_at timestamptz,
    created_by uuid
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $function$
begin
    if auth.uid() is null then raise exception 'Sign in to view invitations'; end if;
    if not public.has_beekeep_workspace_role(target_workspace_id, array['owner', 'admin']) then
        raise exception 'Only workspace owners and admins can view invitations';
    end if;

    return query
    select i.id, i.workspace_id, i.email, i.role, i.status, i.created_at, i.expires_at, i.created_by
    from public.beekeep_workspace_invitations i
    where i.workspace_id = target_workspace_id
      and i.status = 'pending'
      and i.expires_at > now()
    order by i.created_at desc;
end;
$function$;

create or replace function public.beekeep_create_workspace_invitation(
    target_workspace_id uuid,
    invite_email text,
    target_role text default 'member'
)
returns table (
    invitation_id uuid,
    workspace_id uuid,
    email text,
    role text,
    invite_token text,
    expires_at timestamptz
)
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    caller_role text;
    cleaned_email text := lower(trim(coalesce(invite_email, '')));
    cleaned_role text := lower(trim(coalesce(target_role, 'member')));
    token_value text;
    invitation_expiry timestamptz := now() + interval '7 days';
begin
    if caller_id is null then raise exception 'Sign in to invite a beekeeper'; end if;
    if cleaned_email = '' or position('@' in cleaned_email) < 2 or length(cleaned_email) > 254 then
        raise exception 'Enter a valid email address';
    end if;
    if cleaned_role not in ('admin', 'member', 'viewer') then
        raise exception 'Choose Admin, Member or Viewer';
    end if;

    select m.role into caller_role
    from public.beekeep_workspace_members m
    join public.beekeep_workspaces w on w.id = m.workspace_id
    where m.workspace_id = target_workspace_id
      and m.user_id = caller_id
      and w.is_personal = false;

    if caller_role is null then
        raise exception 'Only members of a shared workspace can invite others';
    end if;
    if caller_role is null or caller_role not in ('owner', 'admin') then
        raise exception 'Only workspace owners and admins can invite others';
    end if;
    if caller_role = 'admin' and cleaned_role = 'admin' then
        raise exception 'Only the workspace owner can invite another admin';
    end if;

    if exists (
        select 1
        from auth.users u
        join public.beekeep_workspace_members m on m.user_id = u.id
        where m.workspace_id = target_workspace_id and lower(u.email) = cleaned_email
    ) then
        raise exception 'That email address is already a member of this workspace';
    end if;

    update public.beekeep_workspace_invitations i
    set status = 'expired'
    where i.workspace_id = target_workspace_id and i.status = 'pending' and i.expires_at <= now();

    if exists (
        select 1 from public.beekeep_workspace_invitations i
        where i.workspace_id = target_workspace_id and lower(i.email) = cleaned_email and i.status = 'pending'
    ) then
        raise exception 'There is already a pending invitation for that email';
    end if;

    token_value := encode(gen_random_bytes(32), 'hex');

    insert into public.beekeep_workspace_invitations (
        workspace_id, email, role, token_hash, status, created_by, expires_at
    ) values (
        target_workspace_id, cleaned_email, cleaned_role,
        encode(digest(token_value, 'sha256'), 'hex'), 'pending', caller_id, invitation_expiry
    );

    return query
    select i.id, i.workspace_id, i.email, i.role, token_value, i.expires_at
    from public.beekeep_workspace_invitations i
    where i.workspace_id = target_workspace_id
      and i.email = cleaned_email
      and i.token_hash = encode(digest(token_value, 'sha256'), 'hex')
      and i.status = 'pending';
end;
$function$;

create or replace function public.beekeep_accept_workspace_invitation(invite_token text)
returns table (
    workspace_id uuid,
    workspace_name text,
    workspace_role text
)
language plpgsql
security definer
set search_path = public, pg_temp
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
    on conflict (workspace_id, user_id) do nothing;

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

create or replace function public.beekeep_cancel_workspace_invitation(target_invitation_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    invite_row public.beekeep_workspace_invitations%rowtype;
    caller_role text;
begin
    if auth.uid() is null then raise exception 'Sign in to manage invitations'; end if;

    select i.* into invite_row from public.beekeep_workspace_invitations i
    where i.id = target_invitation_id for update;
    if invite_row.id is null then raise exception 'Invitation not found'; end if;

    select m.role into caller_role from public.beekeep_workspace_members m
    where m.workspace_id = invite_row.workspace_id and m.user_id = auth.uid();
    if caller_role is null or caller_role not in ('owner', 'admin') then raise exception 'Only owners and admins can cancel invitations'; end if;
    if invite_row.status <> 'pending' then raise exception 'Invitation is no longer pending'; end if;
    if caller_role = 'admin' and invite_row.role = 'admin' then
        raise exception 'Only the owner can cancel an admin invitation';
    end if;

    update public.beekeep_workspace_invitations set status = 'cancelled' where id = invite_row.id;
    return true;
end;
$function$;

create or replace function public.beekeep_update_workspace_member_role(
    target_workspace_id uuid,
    target_user_id uuid,
    new_role text
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_role text;
    target_role text;
begin
    if auth.uid() is null then raise exception 'Sign in to manage members'; end if;
    if new_role not in ('admin', 'member', 'viewer') then raise exception 'Choose Admin, Member or Viewer'; end if;

    select m.role into caller_role from public.beekeep_workspace_members m
    join public.beekeep_workspaces w on w.id = m.workspace_id
    where m.workspace_id = target_workspace_id and m.user_id = auth.uid() and w.is_personal = false;
    if caller_role is null or caller_role not in ('owner', 'admin') then raise exception 'Only owners and admins can change member roles'; end if;

    select role into target_role from public.beekeep_workspace_members
    where workspace_id = target_workspace_id and user_id = target_user_id;
    if target_role is null then raise exception 'Workspace member not found'; end if;
    if target_role = 'owner' then raise exception 'Workspace ownership cannot be changed here'; end if;
    if caller_role = 'admin' and (target_role = 'admin' or new_role = 'admin') then
        raise exception 'Only the owner can manage admin roles';
    end if;
    if target_user_id = auth.uid() then raise exception 'You cannot change your own role'; end if;

    update public.beekeep_workspace_members set role = new_role
    where workspace_id = target_workspace_id and user_id = target_user_id;
    return true;
end;
$function$;

create or replace function public.beekeep_remove_workspace_member(
    target_workspace_id uuid,
    target_user_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_role text;
    target_role text;
begin
    if auth.uid() is null then raise exception 'Sign in to manage members'; end if;

    select m.role into caller_role from public.beekeep_workspace_members m
    join public.beekeep_workspaces w on w.id = m.workspace_id
    where m.workspace_id = target_workspace_id and m.user_id = auth.uid() and w.is_personal = false;
    if caller_role is null or caller_role not in ('owner', 'admin') then raise exception 'Only owners and admins can remove members'; end if;

    select role into target_role from public.beekeep_workspace_members
    where workspace_id = target_workspace_id and user_id = target_user_id;
    if target_role is null then raise exception 'Workspace member not found'; end if;
    if target_role = 'owner' then raise exception 'The workspace owner cannot be removed'; end if;
    if target_user_id = auth.uid() then raise exception 'Use workspace transfer or owner tools before leaving your workspace'; end if;
    if caller_role = 'admin' and target_role = 'admin' then
        raise exception 'Only the owner can remove another admin';
    end if;

    delete from public.beekeep_workspace_members
    where workspace_id = target_workspace_id and user_id = target_user_id;
    return true;
end;
$function$;

revoke all on function public.beekeep_list_workspaces() from public, anon;
revoke all on function public.beekeep_create_workspace(text) from public, anon;
revoke all on function public.beekeep_list_workspace_members(uuid) from public, anon;
revoke all on function public.beekeep_list_workspace_invitations(uuid) from public, anon;
revoke all on function public.beekeep_create_workspace_invitation(uuid, text, text) from public, anon;
revoke all on function public.beekeep_accept_workspace_invitation(text) from public, anon;
revoke all on function public.beekeep_cancel_workspace_invitation(uuid) from public, anon;
revoke all on function public.beekeep_update_workspace_member_role(uuid, uuid, text) from public, anon;
revoke all on function public.beekeep_remove_workspace_member(uuid, uuid) from public, anon;

grant execute on function public.beekeep_list_workspaces() to authenticated;
grant execute on function public.beekeep_create_workspace(text) to authenticated;
grant execute on function public.beekeep_list_workspace_members(uuid) to authenticated;
grant execute on function public.beekeep_list_workspace_invitations(uuid) to authenticated;
grant execute on function public.beekeep_create_workspace_invitation(uuid, text, text) to authenticated;
grant execute on function public.beekeep_accept_workspace_invitation(text) to authenticated;
grant execute on function public.beekeep_cancel_workspace_invitation(uuid) to authenticated;
grant execute on function public.beekeep_update_workspace_member_role(uuid, uuid, text) to authenticated;
grant execute on function public.beekeep_remove_workspace_member(uuid, uuid) to authenticated;

commit;
