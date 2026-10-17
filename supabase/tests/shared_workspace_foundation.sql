-- Assertions for supabase/migrations/20261017_beekeep_shared_workspaces.sql.
-- Runs only against the disposable PostgreSQL service in the GitHub Actions workflow.

do $assertions$
declare
    user_one constant uuid := '00000000-0000-0000-0000-000000000001';
    user_two constant uuid := '00000000-0000-0000-0000-000000000002';
    workspace_one uuid;
    workspace_two uuid;
begin
    if (select count(*) from public.beekeep_workspaces where is_personal = true) <> 2 then
        raise exception 'Expected one personal workspace per pre-existing auth user';
    end if;

    if (select count(*) from public.beekeep_workspace_members where role = 'owner') <> 2 then
        raise exception 'Expected one Owner membership per pre-existing user';
    end if;

    select id into workspace_one
    from public.beekeep_workspaces
    where owner_user_id = user_one and is_personal = true;

    select id into workspace_two
    from public.beekeep_workspaces
    where owner_user_id = user_two and is_personal = true;

    if workspace_one is null or workspace_two is null or workspace_one = workspace_two then
        raise exception 'Pre-existing users must have different personal workspaces';
    end if;

    if not exists (
        select 1 from public.beekeep_documents
        where user_id = user_one and entity_type = 'hive' and entity_id = 1
          and workspace_id = workspace_one
    ) then
        raise exception 'User one document was not backfilled to user one workspace';
    end if;

    if not exists (
        select 1 from public.beekeep_documents
        where user_id = user_two and entity_type = 'hive' and entity_id = 1
          and workspace_id = workspace_two
    ) then
        raise exception 'User two document was not backfilled to user two workspace';
    end if;
end;
$assertions$;

-- The auth.users trigger must initialize workspaces for accounts created after migration.
insert into auth.users (id, email, raw_user_meta_data)
values ('00000000-0000-0000-0000-000000000003', 'new-user@example.test', '{}'::jsonb);

do $signup_assertions$
declare
    new_user constant uuid := '00000000-0000-0000-0000-000000000003';
    new_workspace uuid;
begin
    select id into new_workspace
    from public.beekeep_workspaces
    where owner_user_id = new_user and is_personal = true;

    if new_workspace is null then
        raise exception 'New sign-up did not receive a personal workspace';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_members
        where workspace_id = new_workspace and user_id = new_user and role = 'owner'
    ) then
        raise exception 'New sign-up did not receive Owner membership';
    end if;
end;
$signup_assertions$;

-- A current release client may omit workspace_id or accidentally send an arbitrary value.
-- The trigger must force the write into the signed-in user's personal workspace.
begin;
set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', true);

insert into public.beekeep_documents (user_id, entity_type, entity_id, payload, updated_at, workspace_id)
values (
    '00000000-0000-0000-0000-000000000001',
    'hive',
    999,
    '{"id":999,"number":"999"}',
    2000,
    'ffffffff-ffff-ffff-ffff-ffffffffffff'
);

do $private_rls_assertions$
declare
    visible_docs integer;
    visible_workspaces integer;
    visible_members integer;
    own_workspace uuid;
begin
    select count(*) into visible_docs from public.beekeep_documents;
    if visible_docs <> 2 then
        raise exception 'RLS should show user one only their own two cloud documents; found %', visible_docs;
    end if;

    select count(*) into visible_workspaces from public.beekeep_workspaces;
    if visible_workspaces <> 1 then
        raise exception 'RLS should show user one only their personal workspace; found %', visible_workspaces;
    end if;

    select count(*) into visible_members from public.beekeep_workspace_members;
    if visible_members <> 1 then
        raise exception 'RLS should show only the memberships in user one workspace; found %', visible_members;
    end if;

    select id into own_workspace
    from public.beekeep_workspaces
    where owner_user_id = auth.uid() and is_personal = true;

    if not exists (
        select 1 from public.beekeep_documents
        where user_id = auth.uid() and entity_type = 'hive' and entity_id = 999
          and workspace_id = own_workspace
    ) then
        raise exception 'Document-write trigger did not force write into the personal workspace';
    end if;

    if not public.has_beekeep_workspace_role(own_workspace, array['owner']) then
        raise exception 'Owner role check should recognize the workspace owner';
    end if;
end;
$private_rls_assertions$;

-- Mobile clients cannot directly insert membership rows or promote arbitrary users.
do $membership_write_denied$
declare
    own_workspace uuid;
begin
    select id into own_workspace
    from public.beekeep_workspaces
    where owner_user_id = auth.uid() and is_personal = true;

    begin
        insert into public.beekeep_workspace_members (workspace_id, user_id, role)
        values (own_workspace, '00000000-0000-0000-0000-000000000003', 'admin');

        raise exception 'Unexpectedly allowed a mobile client to create a membership';
    exception
        when insufficient_privilege then
            null;
    end;
end;
$membership_write_denied$;

commit;

-- Final administrative assertion ensures a bad user/workspace association was not persisted.
do $final_assertions$
declare
    user_one constant uuid := '00000000-0000-0000-0000-000000000001';
    own_workspace uuid;
begin
    select id into own_workspace
    from public.beekeep_workspaces
    where owner_user_id = user_one and is_personal = true;

    if not exists (
        select 1 from public.beekeep_documents
        where user_id = user_one and entity_type = 'hive' and entity_id = 999
          and workspace_id = own_workspace
    ) then
        raise exception 'Final workspace ownership assertion failed';
    end if;
end;
$final_assertions$;

select 'Shared-workspace foundation migration tests passed' as result;
