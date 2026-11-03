-- Regression coverage for personal and workspace harvest quantity settings.
-- Run after 20261103131228_beekeep_harvest_quantity_settings.sql.
-- All test fixtures are rolled back.

begin;

-- Assert the exposed Data API surface is least-privilege, not merely RLS-protected.
do $grant_assertions$
declare authenticated_privileges text[];
begin
    select array_agg(privilege_type order by privilege_type)
      into authenticated_privileges
      from information_schema.role_table_grants
     where table_schema = 'public'
       and table_name = 'beekeep_action_settings'
       and grantee = 'authenticated';

    if authenticated_privileges is distinct from array['INSERT', 'SELECT', 'UPDATE']::text[] then
        raise exception 'Expected only SELECT/INSERT/UPDATE for authenticated, got %', authenticated_privileges;
    end if;

    if exists (
        select 1
        from information_schema.role_table_grants
        where table_schema = 'public'
          and table_name = 'beekeep_action_settings'
          and grantee in ('anon', 'public')
    ) then
        raise exception 'Anonymous or PUBLIC table grants must not exist for action settings';
    end if;
end;
$grant_assertions$;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000101', 'harvest-settings-owner@example.test',
     '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000102', 'harvest-settings-admin@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000103', 'harvest-settings-member@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000104', 'harvest-settings-viewer@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000105', 'harvest-settings-outsider@example.test', '{}'::jsonb, '{}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

create temporary table harvest_settings_context (
    key text primary key,
    value text not null
);
grant select, insert, update on harvest_settings_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', false);

insert into harvest_settings_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Harvest settings security test');

insert into harvest_settings_context(key, value)
select 'admin_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from harvest_settings_context where key = 'workspace_id'),
    'harvest-settings-admin@example.test',
    'admin'
);

insert into harvest_settings_context(key, value)
select 'member_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from harvest_settings_context where key = 'workspace_id'),
    'harvest-settings-member@example.test',
    'member'
);

insert into harvest_settings_context(key, value)
select 'viewer_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from harvest_settings_context where key = 'workspace_id'),
    'harvest-settings-viewer@example.test',
    'viewer'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000102', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from harvest_settings_context where key = 'admin_token')
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000103', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from harvest_settings_context where key = 'member_token')
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000104', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from harvest_settings_context where key = 'viewer_token')
);

-- Owner can set Apiary Total and Admin can change it back.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', false);
insert into public.beekeep_action_settings (
    scope_key, owner_user_id, workspace_id, harvest_quantity_mode
)
values (
    'workspace:' || (select value from harvest_settings_context where key = 'workspace_id'),
    null,
    (select value::uuid from harvest_settings_context where key = 'workspace_id'),
    'apiary_total'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000102', false);
update public.beekeep_action_settings
set harvest_quantity_mode = 'per_hive'
where scope_key = 'workspace:' || (select value from harvest_settings_context where key = 'workspace_id');

do $admin_assertion$
begin
    if not exists (
        select 1 from public.beekeep_action_settings
        where scope_key = 'workspace:' || (select value from harvest_settings_context where key = 'workspace_id')
          and harvest_quantity_mode = 'per_hive'
          and updated_by = auth.uid()
    ) then
        raise exception 'Workspace Admin could not change the shared harvest setting';
    end if;
end;
$admin_assertion$;

-- A regular member can read the setting but cannot change it or insert a replacement.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000103', false);
do $member_assertion$
declare affected integer; denied boolean := false;
begin
    if not exists (
        select 1 from public.beekeep_action_settings
        where scope_key = 'workspace:' || (select value from harvest_settings_context where key = 'workspace_id')
    ) then
        raise exception 'Workspace member could not read the shared harvest setting';
    end if;

    update public.beekeep_action_settings
    set harvest_quantity_mode = 'apiary_total'
    where scope_key = 'workspace:' || (select value from harvest_settings_context where key = 'workspace_id');
    get diagnostics affected = row_count;
    if affected <> 0 then
        raise exception 'Workspace member unexpectedly changed the harvest setting';
    end if;

    begin
        insert into public.beekeep_action_settings (
            scope_key, owner_user_id, workspace_id, harvest_quantity_mode
        ) values (
            'workspace:' || (select value from harvest_settings_context where key = 'workspace_id'),
            null,
            (select value::uuid from harvest_settings_context where key = 'workspace_id'),
            'apiary_total'
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Workspace member unexpectedly inserted a harvest setting';
    end if;
end;
$member_assertion$;

-- Viewer can also read but cannot write.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000104', false);
do $viewer_assertion$
declare affected integer;
begin
    if not exists (
        select 1 from public.beekeep_action_settings
        where scope_key = 'workspace:' || (select value from harvest_settings_context where key = 'workspace_id')
    ) then
        raise exception 'Workspace viewer could not read the shared harvest setting';
    end if;

    update public.beekeep_action_settings
    set harvest_quantity_mode = 'apiary_total'
    where scope_key = 'workspace:' || (select value from harvest_settings_context where key = 'workspace_id');
    get diagnostics affected = row_count;
    if affected <> 0 then
        raise exception 'Workspace viewer unexpectedly changed the harvest setting';
    end if;
end;
$viewer_assertion$;

-- An unrelated account cannot read the workspace setting.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000105', false);
do $outsider_assertion$
declare visible_count integer;
begin
    select count(*) into visible_count from public.beekeep_action_settings
    where scope_key = 'workspace:' || (select value from harvest_settings_context where key = 'workspace_id');
    if visible_count <> 0 then
        raise exception 'Non-member could read a workspace harvest setting';
    end if;
end;
$outsider_assertion$;

-- Personal setting is private to the owning account.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', false);
insert into public.beekeep_action_settings (
    scope_key, owner_user_id, workspace_id, harvest_quantity_mode
)
values ('personal:' || auth.uid()::text, auth.uid(), null, 'apiary_total');

do $personal_assertion$
begin
    if not exists (
        select 1 from public.beekeep_action_settings
        where scope_key = 'personal:' || auth.uid()::text
          and harvest_quantity_mode = 'apiary_total'
    ) then
        raise exception 'Personal owner could not save their harvest setting';
    end if;
end;
$personal_assertion$;

reset role;
rollback;

select 'Harvest quantity settings security tests passed' as result;
