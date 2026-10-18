-- Account usernames and owner-only team workspace deletion.
-- Usernames are public within a workspace roster, but profile rows are not directly queryable.
-- Workspace deletion cascades only through that shared workspace; personal workspace data is separate.

begin;

create table if not exists public.beekeep_profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    username text not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint beekeep_profiles_username_format
        check (username ~ '^[A-Za-z0-9_]{3,20}$')
);

create unique index if not exists beekeep_profiles_username_lower_idx
    on public.beekeep_profiles (lower(username));

alter table public.beekeep_profiles enable row level security;
revoke all on table public.beekeep_profiles from public, anon, authenticated;

create or replace function public.beekeep_sync_profile_username_from_auth()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    requested_username text := trim(coalesce(new.raw_user_meta_data ->> 'username', ''));
begin
    if requested_username ~ '^[A-Za-z0-9_]{3,20}$' then
        insert into public.beekeep_profiles (user_id, username)
        values (new.id, requested_username)
        on conflict (user_id) do update
        set username = excluded.username,
            updated_at = now();
    end if;
    return new;
end;
$function$;

drop trigger if exists beekeep_sync_profile_username on auth.users;
create trigger beekeep_sync_profile_username
after insert on auth.users
for each row execute function public.beekeep_sync_profile_username_from_auth();

create or replace function public.beekeep_get_username()
returns table (username text)
language sql
stable
security definer
set search_path = public, pg_temp
as $function$
    select coalesce(
        p.username,
        case
            when u.raw_user_meta_data ->> 'username' ~ '^[A-Za-z0-9_]{3,20}
    from auth.users u
    left join public.beekeep_profiles p on p.user_id = u.id
    where u.id = auth.uid();
$function$;

create or replace function public.beekeep_set_username(target_username text)
returns table (username text)
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    cleaned_username text := trim(coalesce(target_username, ''));
begin
    if caller_id is null then
        raise exception 'Sign in to set your username';
    end if;
    if cleaned_username !~ '^[A-Za-z0-9_]{3,20}$' then
        raise exception 'Username must be 3–20 characters using only letters, numbers, or underscores';
    end if;
    if exists (
        select 1 from public.beekeep_profiles p
        where lower(p.username) = lower(cleaned_username)
          and p.user_id <> caller_id
    ) then
        raise exception 'That username is already taken';
    end if;

    insert into public.beekeep_profiles (user_id, username)
    values (caller_id, cleaned_username)
    on conflict (user_id) do update
    set username = excluded.username,
        updated_at = now();

    return query
    select p.username from public.beekeep_profiles p where p.user_id = caller_id;
exception
    when unique_violation then
        raise exception 'That username is already taken';
end;
$function$;

-- Recreate the roster RPC with a username column. The UI falls back to email for older accounts
-- that have not chosen a username yet.
drop function if exists public.beekeep_list_workspace_members(uuid);
create function public.beekeep_list_workspace_members(target_workspace_id uuid)
returns table (
    workspace_id uuid,
    user_id uuid,
    email text,
    username text,
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
    if auth.uid() is null then
        raise exception 'Sign in to view workspace members';
    end if;
    if not public.is_beekeep_workspace_member(target_workspace_id) then
        raise exception 'You are not a member of this workspace';
    end if;

    return query
    select
        m.workspace_id,
        m.user_id,
        coalesce(u.email, ''),
        coalesce(
            p.username,
            case
                when u.raw_user_meta_data ->> 'username' ~ '^[A-Za-z0-9_]{3,20}
        m.role,
        m.joined_at,
        (m.user_id = auth.uid())
    from public.beekeep_workspace_members m
    left join auth.users u on u.id = m.user_id
    left join public.beekeep_profiles p on p.user_id = m.user_id
    where m.workspace_id = target_workspace_id
    order by case m.role when 'owner' then 0 when 'admin' then 1 when 'member' then 2 else 3 end, m.joined_at;
end;
$function$;

create or replace function public.beekeep_delete_workspace(target_workspace_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    target_is_personal boolean;
    caller_role text;
begin
    if caller_id is null then
        raise exception 'Sign in to delete a workspace';
    end if;

    select w.is_personal, m.role
    into target_is_personal, caller_role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m
      on m.workspace_id = w.id
     and m.user_id = caller_id
    where w.id = target_workspace_id
    for update of w;

    if target_is_personal is null then
        raise exception 'Workspace not found or you do not have access';
    end if;
    if target_is_personal then
        raise exception 'Your personal workspace cannot be deleted here';
    end if;
    if caller_role <> 'owner' then
        raise exception 'Only the workspace owner can delete this team';
    end if;

    -- Foreign keys cascade this deletion to the team's members, invitations, and shared documents.
    -- Personal workspaces and personal cloud documents are not children of this team workspace.
    delete from public.beekeep_workspaces
    where id = target_workspace_id and is_personal = false;

    if not found then
        raise exception 'Workspace could not be deleted';
    end if;
    return true;
end;
$function$;

revoke all on function public.beekeep_sync_profile_username_from_auth() from public, anon, authenticated;
revoke all on function public.beekeep_get_username() from public, anon;
revoke all on function public.beekeep_set_username(text) from public, anon;
revoke all on function public.beekeep_list_workspace_members(uuid) from public, anon;
revoke all on function public.beekeep_delete_workspace(uuid) from public, anon;

grant execute on function public.beekeep_get_username() to authenticated;
grant execute on function public.beekeep_set_username(text) to authenticated;
grant execute on function public.beekeep_list_workspace_members(uuid) to authenticated;
grant execute on function public.beekeep_delete_workspace(uuid) to authenticated;

commit;

            then u.raw_user_meta_data ->> 'username'
            else null
        end,
        ''
    ) as username
    from auth.users u
    left join public.beekeep_profiles p on p.user_id = u.id
    where u.id = auth.uid();
$function$;

create or replace function public.beekeep_set_username(target_username text)
returns table (username text)
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    cleaned_username text := trim(coalesce(target_username, ''));
begin
    if caller_id is null then
        raise exception 'Sign in to set your username';
    end if;
    if cleaned_username !~ '^[A-Za-z0-9_]{3,20}$' then
        raise exception 'Username must be 3–20 characters using only letters, numbers, or underscores';
    end if;
    if exists (
        select 1 from public.beekeep_profiles p
        where lower(p.username) = lower(cleaned_username)
          and p.user_id <> caller_id
    ) then
        raise exception 'That username is already taken';
    end if;

    insert into public.beekeep_profiles (user_id, username)
    values (caller_id, cleaned_username)
    on conflict (user_id) do update
    set username = excluded.username,
        updated_at = now();

    return query
    select p.username from public.beekeep_profiles p where p.user_id = caller_id;
exception
    when unique_violation then
        raise exception 'That username is already taken';
end;
$function$;

-- Recreate the roster RPC with a username column. The UI falls back to email for older accounts
-- that have not chosen a username yet.
drop function if exists public.beekeep_list_workspace_members(uuid);
create function public.beekeep_list_workspace_members(target_workspace_id uuid)
returns table (
    workspace_id uuid,
    user_id uuid,
    email text,
    username text,
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
    if auth.uid() is null then
        raise exception 'Sign in to view workspace members';
    end if;
    if not public.is_beekeep_workspace_member(target_workspace_id) then
        raise exception 'You are not a member of this workspace';
    end if;

    return query
    select
        m.workspace_id,
        m.user_id,
        coalesce(u.email, ''),
        coalesce(
            p.username,
            case
                when u.raw_user_meta_data ->> 'username' ~ '^[A-Za-z0-9_]{3,20}$'
                then u.raw_user_meta_data ->> 'username'
                else null
            end,
            nullif(split_part(coalesce(u.email, ''), '@', 1), ''),
            'beekeeper'
        ) as username,
        m.role,
        m.joined_at,
        (m.user_id = auth.uid())
    from public.beekeep_workspace_members m
    left join auth.users u on u.id = m.user_id
    left join public.beekeep_profiles p on p.user_id = m.user_id
    where m.workspace_id = target_workspace_id
    order by case m.role when 'owner' then 0 when 'admin' then 1 when 'member' then 2 else 3 end, m.joined_at;
end;
$function$;

create or replace function public.beekeep_delete_workspace(target_workspace_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    target_is_personal boolean;
    caller_role text;
begin
    if caller_id is null then
        raise exception 'Sign in to delete a workspace';
    end if;

    select w.is_personal, m.role
    into target_is_personal, caller_role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m
      on m.workspace_id = w.id
     and m.user_id = caller_id
    where w.id = target_workspace_id
    for update of w;

    if target_is_personal is null then
        raise exception 'Workspace not found or you do not have access';
    end if;
    if target_is_personal then
        raise exception 'Your personal workspace cannot be deleted here';
    end if;
    if caller_role <> 'owner' then
        raise exception 'Only the workspace owner can delete this team';
    end if;

    -- Foreign keys cascade this deletion to the team's members, invitations, and shared documents.
    -- Personal workspaces and personal cloud documents are not children of this team workspace.
    delete from public.beekeep_workspaces
    where id = target_workspace_id and is_personal = false;

    if not found then
        raise exception 'Workspace could not be deleted';
    end if;
    return true;
end;
$function$;

revoke all on function public.beekeep_sync_profile_username_from_auth() from public, anon, authenticated;
revoke all on function public.beekeep_get_username() from public, anon;
revoke all on function public.beekeep_set_username(text) from public, anon;
revoke all on function public.beekeep_list_workspace_members(uuid) from public, anon;
revoke all on function public.beekeep_delete_workspace(uuid) from public, anon;

grant execute on function public.beekeep_get_username() to authenticated;
grant execute on function public.beekeep_set_username(text) to authenticated;
grant execute on function public.beekeep_list_workspace_members(uuid) to authenticated;
grant execute on function public.beekeep_delete_workspace(uuid) to authenticated;

commit;

                then u.raw_user_meta_data ->> 'username'
                else null
            end,
            ''
        ) as username,
        m.role,
        m.joined_at,
        (m.user_id = auth.uid())
    from public.beekeep_workspace_members m
    left join auth.users u on u.id = m.user_id
    left join public.beekeep_profiles p on p.user_id = m.user_id
    where m.workspace_id = target_workspace_id
    order by case m.role when 'owner' then 0 when 'admin' then 1 when 'member' then 2 else 3 end, m.joined_at;
end;
$function$;

create or replace function public.beekeep_delete_workspace(target_workspace_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    target_is_personal boolean;
    caller_role text;
begin
    if caller_id is null then
        raise exception 'Sign in to delete a workspace';
    end if;

    select w.is_personal, m.role
    into target_is_personal, caller_role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m
      on m.workspace_id = w.id
     and m.user_id = caller_id
    where w.id = target_workspace_id
    for update of w;

    if target_is_personal is null then
        raise exception 'Workspace not found or you do not have access';
    end if;
    if target_is_personal then
        raise exception 'Your personal workspace cannot be deleted here';
    end if;
    if caller_role <> 'owner' then
        raise exception 'Only the workspace owner can delete this team';
    end if;

    -- Foreign keys cascade this deletion to the team's members, invitations, and shared documents.
    -- Personal workspaces and personal cloud documents are not children of this team workspace.
    delete from public.beekeep_workspaces
    where id = target_workspace_id and is_personal = false;

    if not found then
        raise exception 'Workspace could not be deleted';
    end if;
    return true;
end;
$function$;

revoke all on function public.beekeep_sync_profile_username_from_auth() from public, anon, authenticated;
revoke all on function public.beekeep_get_username() from public, anon;
revoke all on function public.beekeep_set_username(text) from public, anon;
revoke all on function public.beekeep_list_workspace_members(uuid) from public, anon;
revoke all on function public.beekeep_delete_workspace(uuid) from public, anon;

grant execute on function public.beekeep_get_username() to authenticated;
grant execute on function public.beekeep_set_username(text) to authenticated;
grant execute on function public.beekeep_list_workspace_members(uuid) to authenticated;
grant execute on function public.beekeep_delete_workspace(uuid) to authenticated;

commit;

            then u.raw_user_meta_data ->> 'username'
            else null
        end,
        ''
    ) as username
    from auth.users u
    left join public.beekeep_profiles p on p.user_id = u.id
    where u.id = auth.uid();
$function$;

create or replace function public.beekeep_set_username(target_username text)
returns table (username text)
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    cleaned_username text := trim(coalesce(target_username, ''));
begin
    if caller_id is null then
        raise exception 'Sign in to set your username';
    end if;
    if cleaned_username !~ '^[A-Za-z0-9_]{3,20}$' then
        raise exception 'Username must be 3–20 characters using only letters, numbers, or underscores';
    end if;
    if exists (
        select 1 from public.beekeep_profiles p
        where lower(p.username) = lower(cleaned_username)
          and p.user_id <> caller_id
    ) then
        raise exception 'That username is already taken';
    end if;

    insert into public.beekeep_profiles (user_id, username)
    values (caller_id, cleaned_username)
    on conflict (user_id) do update
    set username = excluded.username,
        updated_at = now();

    return query
    select p.username from public.beekeep_profiles p where p.user_id = caller_id;
exception
    when unique_violation then
        raise exception 'That username is already taken';
end;
$function$;

-- Recreate the roster RPC with a username column. The UI falls back to email for older accounts
-- that have not chosen a username yet.
drop function if exists public.beekeep_list_workspace_members(uuid);
create function public.beekeep_list_workspace_members(target_workspace_id uuid)
returns table (
    workspace_id uuid,
    user_id uuid,
    email text,
    username text,
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
    if auth.uid() is null then
        raise exception 'Sign in to view workspace members';
    end if;
    if not public.is_beekeep_workspace_member(target_workspace_id) then
        raise exception 'You are not a member of this workspace';
    end if;

    return query
    select
        m.workspace_id,
        m.user_id,
        coalesce(u.email, ''),
        coalesce(
            p.username,
            case
                when u.raw_user_meta_data ->> 'username' ~ '^[A-Za-z0-9_]{3,20}$'
                then u.raw_user_meta_data ->> 'username'
                else null
            end,
            nullif(split_part(coalesce(u.email, ''), '@', 1), ''),
            'beekeeper'
        ) as username,
        m.role,
        m.joined_at,
        (m.user_id = auth.uid())
    from public.beekeep_workspace_members m
    left join auth.users u on u.id = m.user_id
    left join public.beekeep_profiles p on p.user_id = m.user_id
    where m.workspace_id = target_workspace_id
    order by case m.role when 'owner' then 0 when 'admin' then 1 when 'member' then 2 else 3 end, m.joined_at;
end;
$function$;

create or replace function public.beekeep_delete_workspace(target_workspace_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    target_is_personal boolean;
    caller_role text;
begin
    if caller_id is null then
        raise exception 'Sign in to delete a workspace';
    end if;

    select w.is_personal, m.role
    into target_is_personal, caller_role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m
      on m.workspace_id = w.id
     and m.user_id = caller_id
    where w.id = target_workspace_id
    for update of w;

    if target_is_personal is null then
        raise exception 'Workspace not found or you do not have access';
    end if;
    if target_is_personal then
        raise exception 'Your personal workspace cannot be deleted here';
    end if;
    if caller_role <> 'owner' then
        raise exception 'Only the workspace owner can delete this team';
    end if;

    -- Foreign keys cascade this deletion to the team's members, invitations, and shared documents.
    -- Personal workspaces and personal cloud documents are not children of this team workspace.
    delete from public.beekeep_workspaces
    where id = target_workspace_id and is_personal = false;

    if not found then
        raise exception 'Workspace could not be deleted';
    end if;
    return true;
end;
$function$;

revoke all on function public.beekeep_sync_profile_username_from_auth() from public, anon, authenticated;
revoke all on function public.beekeep_get_username() from public, anon;
revoke all on function public.beekeep_set_username(text) from public, anon;
revoke all on function public.beekeep_list_workspace_members(uuid) from public, anon;
revoke all on function public.beekeep_delete_workspace(uuid) from public, anon;

grant execute on function public.beekeep_get_username() to authenticated;
grant execute on function public.beekeep_set_username(text) to authenticated;
grant execute on function public.beekeep_list_workspace_members(uuid) to authenticated;
grant execute on function public.beekeep_delete_workspace(uuid) to authenticated;

commit;
