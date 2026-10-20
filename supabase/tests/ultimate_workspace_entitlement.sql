-- Regression tests for Ultimate-only workspace ownership with subscription-free invitees.
-- Run after 20261020031200_beekeep_ultimate_workspace_entitlement.sql on disposable PostgreSQL.

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
    -- A user-editable metadata spoof must not grant authorization.
    ('00000000-0000-0000-0000-000000000011', 'ultimate-gate-free@example.test', '{}'::jsonb,
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb),
    ('00000000-0000-0000-0000-000000000012', 'ultimate-gate-pro@example.test',
        '{"beekeep_plan":"pro","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000013', 'ultimate-gate-owner@example.test',
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000014', 'ultimate-gate-worker@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000015', 'ultimate-gate-expired@example.test',
        '{"beekeep_plan":"ultimate","beekeep_subscription_expires_at":"2000-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

create temporary table ultimate_workspace_test_context (
    key text primary key,
    value text not null
);
grant select, insert, update on ultimate_workspace_test_context to authenticated;

set role authenticated;

-- Free, Pro, and expired Ultimate accounts must not create workspaces.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000011', false);
do $free_owner_assertions$
declare denied boolean := false; error_message text;
begin
    begin
        perform * from public.beekeep_create_workspace('Free owner must be blocked');
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%active BeeKeep Ultimate subscription is required%' then
            raise exception 'Unexpected Free-plan workspace rejection: %', error_message;
        end if;
        denied := true;
    end;
    if not denied then raise exception 'Free account unexpectedly created a team workspace'; end if;
end;
$free_owner_assertions$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000012', false);
do $pro_owner_assertions$
declare denied boolean := false; error_message text;
begin
    begin
        perform * from public.beekeep_create_workspace('Pro owner must be blocked');
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%active BeeKeep Ultimate subscription is required%' then
            raise exception 'Unexpected Pro-plan workspace rejection: %', error_message;
        end if;
        denied := true;
    end;
    if not denied then raise exception 'Pro account unexpectedly created a team workspace'; end if;
end;
$pro_owner_assertions$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000015', false);
do $expired_owner_assertions$
declare denied boolean := false;
begin
    begin
        perform * from public.beekeep_create_workspace('Expired Ultimate must be blocked');
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then raise exception 'Expired Ultimate account unexpectedly created a team workspace'; end if;
end;
$expired_owner_assertions$;

-- An active Ultimate owner may create a team and invite a worker who has no paid plan.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000013', false);
insert into ultimate_workspace_test_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Ultimate owner test workspace');

insert into ultimate_workspace_test_context(key, value)
select 'invite_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id'),
    'ultimate-gate-worker@example.test',
    'member'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000014', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from ultimate_workspace_test_context where key = 'invite_token')
);

do $member_entitlement_assertions$
declare active_value boolean;
begin
    select active into active_value from public.beekeep_my_active_ultimate_entitlement();
    if coalesce(active_value, true) then
        raise exception 'The invited worker unexpectedly has an Ultimate entitlement';
    end if;
    if not exists (
        select 1 from public.beekeep_workspace_members
        where workspace_id = (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id')
          and user_id = auth.uid()
          and role = 'member'
    ) then
        raise exception 'Free invited worker did not join the team';
    end if;
end;
$member_entitlement_assertions$;

-- A non-Ultimate invited worker can collaborate while the workspace owner is active.
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
) values (
    (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id'),
    'hive', 91001, '{"id":91001,"number":"Owner hive"}', 1000, false, 'ultimate-gate-test',
    '00000000-0000-0000-0000-000000000013'
);

insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
) values (
    (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id'),
    'hive', 91002, '{"id":91002,"number":"Worker hive"}', 2000, false, 'ultimate-gate-test',
    auth.uid()
);

do $active_owner_worker_access$
declare visible_count integer;
begin
    select count(*) into visible_count
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id')
      and entity_type = 'hive';
    if visible_count <> 2 then
        raise exception 'Non-Ultimate worker should be able to access shared records while owner Ultimate is active; saw %', visible_count;
    end if;
end;
$active_owner_worker_access$;

-- Simulate subscription expiry/downgrade through trusted server metadata.
reset role;
update auth.users
set raw_app_meta_data = '{"beekeep_plan":"pro","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb
where id = '00000000-0000-0000-0000-000000000013';
set role authenticated;

-- The owner may no longer create invitations after losing Ultimate.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000013', false);
do $expired_invite_assertions$
declare denied boolean := false;
begin
    begin
        perform * from public.beekeep_create_workspace_invitation(
            (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id'),
            'another-worker@example.test',
            'member'
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then raise exception 'Workspace owner without Ultimate unexpectedly invited a teammate'; end if;
end;
$expired_invite_assertions$;

-- Workers retain read access to existing data, but cannot write while the owner subscription is inactive.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000014', false);
do $expired_owner_write_assertions$
declare denied boolean := false; visible_count integer;
begin
    select count(*) into visible_count
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id');
    if visible_count <> 2 then
        raise exception 'Existing team records should remain readable after owner entitlement lapses';
    end if;

    begin
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
        ) values (
            (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id'),
            'hive', 91003, '{"id":91003}', 3000, false, 'ultimate-gate-test', auth.uid()
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then raise exception 'Worker unexpectedly wrote shared data while owner Ultimate was inactive'; end if;
end;
$expired_owner_write_assertions$;

reset role;
update auth.users
set raw_app_meta_data = '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb
where id = '00000000-0000-0000-0000-000000000013';
set role authenticated;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000014', false);
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
) values (
    (select value::uuid from ultimate_workspace_test_context where key = 'workspace_id'),
    'hive', 91004, '{"id":91004,"number":"Worker resumed"}', 4000, false, 'ultimate-gate-test',
    auth.uid()
);

reset role;
select 'Ultimate workspace entitlement tests passed' as result;
