-- Username/profile and workspace deletion security assertions.
-- Runs against the disposable PostgreSQL service after all shared-workspace migrations.

-- New sign-ups can store a username on auth.users metadata and get a profile row.
insert into auth.users (id, email, raw_user_meta_data)
values (
    '00000000-0000-0000-0000-000000000004',
    'username-trigger@example.test',
    '{"username":"NewBee_42"}'::jsonb
);

do $new_profile_assertions$
begin
    if not exists (
        select 1 from public.beekeep_profiles
        where user_id = '00000000-0000-0000-0000-000000000004'
          and username = 'NewBee_42'
    ) then
        raise exception 'Sign-up metadata did not initialize the username profile';
    end if;
end;
$new_profile_assertions$;

create temporary table stage_username_delete_context (
    key text primary key,
    value text
);
grant select, insert, update on stage_username_delete_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

select * from public.beekeep_set_username('BeeBoss_1');

insert into stage_username_delete_context(key, value)
select 'personal_workspace', id::text
from public.beekeep_list_workspaces()
where is_personal = true;

insert into stage_username_delete_context(key, value)
select 'team_workspace', id::text
from public.beekeep_create_workspace('Workspace Deletion Test');

-- Team member lists return the username and never require clients to query auth.users.
do $roster_assertions$
declare
    workspace_value uuid := (select value::uuid from stage_username_delete_context where key = 'team_workspace');
    username_value text;
    member_count integer;
begin
    select username into username_value
    from public.beekeep_list_workspace_members(workspace_value)
    where user_id = '00000000-0000-0000-0000-000000000001';

    select count(*) into member_count from public.beekeep_list_workspace_members(workspace_value);

    if username_value <> 'BeeBoss_1' then
        raise exception 'Workspace roster did not return the owner's selected username';
    end if;
    if member_count <> 1 then
        raise exception 'Unexpected member count in a newly-created workspace: %', member_count;
    end if;
end;
$roster_assertions$;

-- Username uniqueness is case-insensitive.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
do $duplicate_username_assertions$
declare denied boolean := false; error_message text;
begin
    begin
        perform * from public.beekeep_set_username('beeboss_1');
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%already taken%' then
            raise exception 'Unexpected duplicate username rejection: %', error_message;
        end if;
        denied := true;
    end;
    if not denied then
        raise exception 'Duplicate username was unexpectedly accepted';
    end if;
end;
$duplicate_username_assertions$;

-- A signed-in non-member cannot delete the workspace.
do $non_member_delete_assertions$
declare denied boolean := false; error_message text;
begin
    begin
        perform public.beekeep_delete_workspace(
            (select value::uuid from stage_username_delete_context where key = 'team_workspace')
        );
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%Workspace not found or you do not have access%' then
            raise exception 'Unexpected non-member deletion rejection: %', error_message;
        end if;
        denied := true;
    end;
    if not denied then
        raise exception 'Non-member was unexpectedly allowed to delete a workspace';
    end if;
end;
$non_member_delete_assertions$;

-- The owner still cannot delete their private personal workspace through this RPC.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
do $personal_delete_assertions$
declare denied boolean := false; error_message text;
begin
    begin
        perform public.beekeep_delete_workspace(
            (select value::uuid from stage_username_delete_context where key = 'personal_workspace')
        );
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%personal workspace cannot be deleted%' then
            raise exception 'Unexpected personal workspace deletion rejection: %', error_message;
        end if;
        denied := true;
    end;
    if not denied then
        raise exception 'Personal workspace was unexpectedly deletable';
    end if;
end;
$personal_delete_assertions$;

select public.beekeep_delete_workspace(
    (select value::uuid from stage_username_delete_context where key = 'team_workspace')
);

reset role;

do $deletion_assertions$
declare
    team_id uuid := (select value::uuid from stage_username_delete_context where key = 'team_workspace');
    personal_id uuid := (select value::uuid from stage_username_delete_context where key = 'personal_workspace');
begin
    if exists (select 1 from public.beekeep_workspaces where id = team_id) then
        raise exception 'Owner deletion did not remove the shared workspace';
    end if;
    if exists (select 1 from public.beekeep_workspace_members where workspace_id = team_id) then
        raise exception 'Workspace deletion did not cascade to memberships';
    end if;
    if not exists (
        select 1 from public.beekeep_workspaces
        where id = personal_id and is_personal = true
          and owner_user_id = '00000000-0000-0000-0000-000000000001'
    ) then
        raise exception 'Deleting a team workspace affected the owner's personal workspace';
    end if;
    if not exists (
        select 1 from public.beekeep_profiles
        where user_id = '00000000-0000-0000-0000-000000000001'
          and username = 'BeeBoss_1'
    ) then
        raise exception 'Workspace deletion unexpectedly removed the owner's profile';
    end if;
end;
$deletion_assertions$;

select 'Username and workspace deletion security tests passed' as result;
