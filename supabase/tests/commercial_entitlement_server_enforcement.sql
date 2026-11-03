-- Regression tests for Commercial entitlements, invitation acceptance, owner quota lookup,
-- NFC downgrade protection, and server-enforced quotas.
-- Run after the 2026110204* Commercial entitlement migrations and the owner-quota RPC migration.
-- The entire fixture is rolled back, including users, workspaces, invitations and documents.
begin;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000081', 'commercial-regression-owner@example.test',
     '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000082', 'commercial-regression-admin@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000083', 'commercial-regression-pending@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000084', 'commercial-regression-hobbyist@example.test', '{}'::jsonb,
     '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb),
    ('00000000-0000-0000-0000-000000000085', 'commercial-regression-legacy-pro@example.test',
     '{"beekeep_plan":"pro_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000086', 'commercial-regression-expired@example.test',
     '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2020-01-01T00:00:00Z"}'::jsonb,
     '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

-- Verify the selected team carries the owner's approved custom hive allowance.
insert into public.beekeep_user_hive_limit_overrides (user_id, hive_limit_override, reason)
values (
    '00000000-0000-0000-0000-000000000081',
    11000,
    'Regression: workspace owner custom hive allowance'
)
on conflict (user_id) do update
set hive_limit_override = excluded.hive_limit_override,
    reason = excluded.reason;

do $plan_resolution_assertions$
begin
    if public.beekeep_server_base_hive_limit('00000000-0000-0000-0000-000000000081') <> 10000 then
        raise exception 'Active Commercial must receive the 10,000 hive base limit';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000081') <> 11000 then
        raise exception 'Workspace owner custom hive allowance must resolve to 11,000';
    end if;
    if public.beekeep_resolved_apiary_limit('00000000-0000-0000-0000-000000000081') <> 1000 then
        raise exception 'Active Commercial must receive the 1,000 apiary limit';
    end if;
    if public.beekeep_server_base_hive_limit('00000000-0000-0000-0000-000000000084') <> 15 then
        raise exception 'Hobbyist must receive the 15 hive limit';
    end if;
    if public.beekeep_resolved_apiary_limit('00000000-0000-0000-0000-000000000084') <> 3 then
        raise exception 'Hobbyist must receive the 3 apiary limit';
    end if;
    if public.beekeep_server_base_hive_limit('00000000-0000-0000-0000-000000000085') <> 15 then
        raise exception 'Legacy Pro must resolve to Hobbyist, not a paid allowance';
    end if;
    if public.beekeep_user_has_active_commercial('00000000-0000-0000-0000-000000000085') then
        raise exception 'Legacy Pro must not receive Commercial features';
    end if;
    if public.beekeep_server_base_hive_limit('00000000-0000-0000-0000-000000000084') <> 15
       or public.beekeep_user_has_active_commercial('00000000-0000-0000-0000-000000000084') then
        raise exception 'User-editable metadata must not grant Commercial features or quotas';
    end if;
    if public.beekeep_server_base_hive_limit('00000000-0000-0000-0000-000000000086') <> 15
       or public.beekeep_resolved_apiary_limit('00000000-0000-0000-0000-000000000086') <> 3
       or public.beekeep_user_has_active_commercial('00000000-0000-0000-0000-000000000086') then
        raise exception 'An expired Commercial subscription must resolve to Hobbyist limits';
    end if;
end;
$plan_resolution_assertions$;

create temporary table commercial_entitlement_test_context (
    key text primary key,
    value text not null
);
grant select, insert, update on commercial_entitlement_test_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000081', false);

-- An active Commercial owner can create a team and invite an admin plus a second pending member.
insert into commercial_entitlement_test_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Commercial entitlement regression');

do $owner_workspace_status$
declare active_value boolean;
begin
    select active into active_value
      from public.beekeep_workspace_owner_commercial_entitlement(
          (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
      );
    if active_value is distinct from true then
        raise exception 'The Commercial workspace owner should see an active workspace entitlement';
    end if;
end;
$owner_workspace_status$;

do $owner_workspace_quota_assertion$
declare quota_row record;
begin
    select * into quota_row
      from public.beekeep_workspace_owner_quota_entitlement(
          (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
      );
    if quota_row.active is distinct from true
       or quota_row.effective_hive_limit <> 11000
       or quota_row.effective_apiary_limit <> 1000
       or quota_row.member_role <> 'owner' then
        raise exception 'A Commercial owner should receive their own resolved workspace quotas.';
    end if;
end;
$owner_workspace_quota_assertion$;

insert into commercial_entitlement_test_context(key, value)
select 'admin_invitation_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
    'commercial-regression-admin@example.test',
    'admin'
);

insert into commercial_entitlement_test_context(key, value)
select 'pending_invitation_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
    'commercial-regression-pending@example.test',
    'member'
);

-- A non-member cannot read the workspace owner's limits by guessing a workspace ID.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000084', false);
do $nonmember_quota_assertion$
declare row_count integer;
begin
    select count(*) into row_count
      from public.beekeep_workspace_owner_quota_entitlement(
          (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
      );
    if row_count <> 0 then
        raise exception 'A non-member received another workspace owner quota';
    end if;
end;
$nonmember_quota_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000082', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from commercial_entitlement_test_context where key = 'admin_invitation_token')
);

do $invited_member_workspace_quota_assertion$
declare quota_row record;
begin
    select * into quota_row
      from public.beekeep_workspace_owner_quota_entitlement(
          (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
      );
    if quota_row.active is distinct from true
       or quota_row.effective_hive_limit <> 11000
       or quota_row.effective_apiary_limit <> 1000
       or quota_row.member_role <> 'admin' then
        raise exception 'An invited Hobbyist must receive the workspace quota of 11,000 hives and 1,000 apiaries.';
    end if;
end;
$invited_member_workspace_quota_assertion$;

do $invited_member_workspace_status$
declare active_value boolean;
begin
    select active into active_value
      from public.beekeep_workspace_owner_commercial_entitlement(
          (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
      );
    if active_value is distinct from true then
        raise exception 'An invited member should see the active Commercial status of the workspace owner';
    end if;
end;
$invited_member_workspace_status$;

-- An invited admin can collaborate while the owner is Commercial.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000081', false);
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
) values (
    (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
    'hive', 98001, '{"id":98001,"number":"Existing shared hive"}', 1000, false,
    'commercial-entitlement-test', auth.uid()
);

-- The active Commercial owner may sync an NFC assignment to personal cloud storage.
insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
) values (
    auth.uid(), 'nfc_assignment', 97001, '{"id":97001,"hiveId":98001,"tag":"test"}',
    1000, false, 'commercial-entitlement-test'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000082', false);
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
) values (
    (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
    'event', 98002, '{"id":98002,"title":"Admin shared event"}', 2000, false,
    'commercial-entitlement-test', auth.uid()
);

-- A Hobbyist invited admin inherits the workspace owner's Commercial entitlement
-- for shared NFC assignments. This record belongs to the team, not the admin's
-- personal cloud document collection.
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
) values (
    (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
    'nfc_assignment', 97002,
    '{"id":97002,"tag_uid":"A1B2C3D4","hive_id":98001,"assigned_at":2000}',
    2000, false, 'commercial-entitlement-test', auth.uid()
);

-- The owner is Commercial, so the shared workspace may hold up to 1,000 apiaries.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000081', false);
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
select
    (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
    'apiary',
    n,
    '{"id":' || n::text || ',"name":"Commercial apiary"}',
    n,
    false,
    'commercial-entitlement-test',
    auth.uid()
from generate_series(1, 1000) as n;

do $commercial_workspace_apiary_quota_assertion$
declare denied boolean := false;
begin
    begin
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
        ) values (
            (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
            'apiary', 1001, '{"id":1001,"name":"Over quota"}', 1001, false,
            'commercial-entitlement-test', auth.uid()
        );
    exception when check_violation then
        denied := true;
    end;
    if not denied then
        raise exception 'The 1,001st Commercial team apiary was not rejected by the server';
    end if;
end;
$commercial_workspace_apiary_quota_assertion$;

-- Simulate the owner losing Commercial through trusted app metadata.
reset role;
update auth.users
   set raw_app_meta_data = '{"beekeep_plan":"hobbyist"}'::jsonb
 where id = '00000000-0000-0000-0000-000000000081';

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000082', false);

do $downgraded_workspace_status$
declare active_value boolean;
begin
    select active into active_value
      from public.beekeep_workspace_owner_commercial_entitlement(
          (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
      );
    if active_value is distinct from false then
        raise exception 'Existing invited members must see the workspace as locked after owner downgrade';
    end if;
end;
$downgraded_workspace_status$;

do $downgraded_workspace_quota_assertion$
declare quota_row record;
begin
    select * into quota_row
      from public.beekeep_workspace_owner_quota_entitlement(
          (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
      );
    if quota_row.active is distinct from false
       or quota_row.effective_hive_limit <> 15
       or quota_row.effective_apiary_limit <> 3 then
        raise exception 'A downgraded workspace must expose Hobbyist limits and ignore the previous Commercial override.';
    end if;
end;
$downgraded_workspace_quota_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000083', false);

-- The pending invitation must not grant membership after the owner downgrades.
do $pending_invite_downgrade_assertion$
declare denied boolean := false;
begin
    begin
        perform * from public.beekeep_accept_workspace_invitation(
            (select value from commercial_entitlement_test_context where key = 'pending_invitation_token')
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'A pending invitation was accepted after the workspace owner lost Commercial';
    end if;
    if exists (
        select 1 from public.beekeep_workspace_members
         where workspace_id = (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
           and user_id = auth.uid()
    ) then
        raise exception 'The downgraded workspace invitation created membership';
    end if;
end;
$pending_invite_downgrade_assertion$;

-- A Hobbyist can still read an existing NFC assignment but cannot insert or edit one.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000081', false);
do $personal_nfc_downgrade_assertion$
declare visible_count integer; denied boolean := false;
begin
    select count(*) into visible_count
      from public.beekeep_documents
     where user_id = auth.uid()
       and entity_type = 'nfc_assignment'
       and entity_id = 97001
       and not deleted;
    if visible_count <> 1 then
        raise exception 'Existing NFC assignments must remain readable after downgrade';
    end if;

    begin
        insert into public.beekeep_documents (
            user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
        ) values (
            auth.uid(), 'nfc_assignment', 97002, '{"id":97002,"hiveId":98001}',
            2000, false, 'commercial-entitlement-test'
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Hobbyist unexpectedly created a new NFC assignment';
    end if;

    denied := false;
    begin
        update public.beekeep_documents
           set payload = '{"id":97001,"hiveId":98001,"tag":"changed"}',
               updated_at = 3000
         where user_id = auth.uid()
           and entity_type = 'nfc_assignment'
           and entity_id = 97001;
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Hobbyist unexpectedly edited an existing NFC assignment';
    end if;
end;
$personal_nfc_downgrade_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000082', false);

-- The workspace's existing NFC assignments remain readable after owner downgrade,
-- but Hobbyist team members can no longer create or change them.
do $workspace_nfc_downgrade_assertion$
declare visible_count integer; affected integer; denied boolean := false;
begin
    select count(*) into visible_count
      from public.beekeep_workspace_documents
     where workspace_id = (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
       and entity_type = 'nfc_assignment'
       and entity_id = 97002
       and not deleted;
    if visible_count <> 1 then
        raise exception 'Existing workspace NFC assignments must remain readable after owner downgrade';
    end if;

    update public.beekeep_workspace_documents
       set payload = '{"id":97002,"tag_uid":"A1B2C3D4","hive_id":98001,"changed":true}',
           updated_at = 3000,
           updated_by = auth.uid()
     where workspace_id = (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
       and entity_type = 'nfc_assignment'
       and entity_id = 97002;
    get diagnostics affected = row_count;
    if affected <> 0 then
        raise exception 'A member edited a shared NFC assignment after owner downgrade';
    end if;

    begin
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
        ) values (
            (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
            'nfc_assignment', 97003,
            '{"id":97003,"tag_uid":"D4C3B2A1","hive_id":98001,"assigned_at":3000}',
            3000, false, 'commercial-entitlement-test', auth.uid()
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'A member created a shared NFC assignment after owner downgrade';
    end if;
end;
$workspace_nfc_downgrade_assertion$;

-- Existing members retain read access, but inserts and updates are denied.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000082', false);
do $read_only_team_assertions$
declare visible_count integer; affected integer; denied boolean := false;
begin
    select count(*) into visible_count
      from public.beekeep_workspace_documents
     where workspace_id = (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
       and entity_type in ('hive', 'event');
    if visible_count <> 2 then
        raise exception 'Existing shared records must remain readable after owner downgrade';
    end if;

    update public.beekeep_workspace_documents
       set payload = '{"id":98002,"title":"This update must not persist"}',
           updated_at = 3000,
           updated_by = auth.uid()
     where workspace_id = (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
       and entity_type = 'event'
       and entity_id = 98002;
    get diagnostics affected = row_count;
    if affected <> 0 then
        raise exception 'An invited admin updated a shared record after owner downgrade';
    end if;

    begin
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
        ) values (
            (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
            'hive', 98003, '{"id":98003}', 4000, false, 'commercial-entitlement-test', auth.uid()
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'An invited admin inserted a shared record after owner downgrade';
    end if;
end;
$read_only_team_assertions$;

-- SECURITY DEFINER workspace-management RPCs must not bypass the owner entitlement.
do $workspace_management_assertions$
declare denied boolean := false;
begin
    begin
        perform public.beekeep_update_workspace_member_role(
            (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id'),
            '00000000-0000-0000-0000-000000000083',
            'viewer'
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Invited admin changed a role after owner downgrade';
    end if;

    denied := false;
    begin
        perform public.beekeep_cancel_workspace_invitation(
            (select i.id
               from public.beekeep_workspace_invitations i
              where i.workspace_id = (select value::uuid from commercial_entitlement_test_context where key = 'workspace_id')
                and i.status = 'pending'
              limit 1)
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Invited admin cancelled a pending invitation after owner downgrade';
    end if;
end;
$workspace_management_assertions$;

-- Personal Hobbyist sync allows the existing 15-hive / 3-apiary ceiling but rejects net-new
-- active records above it. Each insert batch is atomic, and all fixtures are rolled back below.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000084', false);

insert into public.beekeep_documents (user_id, entity_type, entity_id, payload, updated_at, deleted, device_id)
select auth.uid(), 'apiary', n, '{"id":' || n::text || ',"name":"Test apiary"}', n, false, 'quota-test'
  from generate_series(1, 3) as n;

do $apiary_quota_assertion$
declare denied boolean := false;
begin
    begin
        insert into public.beekeep_documents (
            user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
        ) values (
            auth.uid(), 'apiary', 4, '{"id":4,"name":"Over quota"}', 4, false, 'quota-test'
        );
    exception when check_violation then
        denied := true;
    end;
    if not denied then
        raise exception 'The fourth Hobbyist apiary was not rejected by the server';
    end if;
end;
$apiary_quota_assertion$;

insert into public.beekeep_documents (user_id, entity_type, entity_id, payload, updated_at, deleted, device_id)
select auth.uid(), 'hive', n, '{"id":' || n::text || ',"number":"' || n::text || '"}', n, false, 'quota-test'
  from generate_series(1, 15) as n;

do $hive_quota_assertion$
declare denied boolean := false;
begin
    begin
        insert into public.beekeep_documents (
            user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
        ) values (
            auth.uid(), 'hive', 16, '{"id":16,"number":"16"}', 16, false, 'quota-test'
        );
    exception when check_violation then
        denied := true;
    end;
    if not denied then
        raise exception 'The sixteenth Hobbyist hive was not rejected by the server';
    end if;
end;
$hive_quota_assertion$;

reset role;
rollback;

select 'Commercial entitlement, invitation, workspace-write and Hobbyist quota tests passed' as result;
