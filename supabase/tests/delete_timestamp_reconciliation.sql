-- Verify deliberate deletes survive device clock skew and concurrent cloud writes.
-- Run after 20261101203000_beekeep_delete_timestamp_reconciliation.sql.
begin;

create temporary table delete_timestamp_test_context (
    key text primary key,
    value text not null
);
grant select, insert, update, delete on delete_timestamp_test_context to authenticated;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
values (
    auth.uid(), 'delete_clock_test', 99621,
    '{"id":99621,"value":"cloud copy from device with fast clock"}',
    9000, false, 'fast-clock-device'
);

insert into delete_timestamp_test_context(key, value)
select 'personal_version_before_delete', current_version::text
from public.beekeep_personal_sync_state where user_id = auth.uid();

-- Device A's explicit delete has a much older local timestamp than Device B's row.
update public.beekeep_documents
set payload = '{"id":99621,"deleted":true}',
    updated_at = 1000,
    deleted = true,
    device_id = 'slow-clock-delete-device'
where user_id = auth.uid()
  and entity_type = 'delete_clock_test'
  and entity_id = 99621;

do $personal_delete_assertion$
declare
    row_deleted boolean;
    row_timestamp bigint;
    observed_version bigint;
    previous_version bigint;
begin
    select deleted, updated_at into row_deleted, row_timestamp
    from public.beekeep_documents
    where user_id = auth.uid()
      and entity_type = 'delete_clock_test'
      and entity_id = 99621;
    select s.current_version into observed_version
    from public.beekeep_personal_sync_state as s
    where s.user_id = auth.uid();
    select value::bigint into previous_version
    from delete_timestamp_test_context
    where key = 'personal_version_before_delete';

    if row_deleted is distinct from true or row_timestamp <= 9000 then
        raise exception 'An explicit personal deletion was discarded or not advanced past the cloud row';
    end if;
    if observed_version <= previous_version then
        raise exception 'The accepted personal tombstone did not advance incremental sync state';
    end if;
end;
$personal_delete_assertion$;

-- A delayed active update cannot revive the permanently deleted entity.
update public.beekeep_documents
set payload = '{"id":99621,"value":"resurrected"}',
    updated_at = 999999,
    deleted = false
where user_id = auth.uid()
  and entity_type = 'delete_clock_test'
  and entity_id = 99621;

do $personal_no_resurrection_assertion$
declare
    row_deleted boolean;
begin
    select deleted into row_deleted
    from public.beekeep_documents
    where user_id = auth.uid()
      and entity_type = 'delete_clock_test'
      and entity_id = 99621;
    if row_deleted is distinct from true then
        raise exception 'A deleted personal entity was resurrected';
    end if;
end;
$personal_no_resurrection_assertion$;

reset role;
update auth.users
set raw_app_meta_data = jsonb_build_object(
    'beekeep_plan', 'ultimate_yearly',
    'beekeep_subscription_expires_at', '2099-01-01T00:00:00Z'
)
where id = '00000000-0000-0000-0000-000000000001';

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
insert into delete_timestamp_test_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Delete Timestamp Test');

insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    (select value::uuid from delete_timestamp_test_context where key = 'workspace_id'),
    'delete_clock_test', 99622,
    '{"id":99622,"value":"workspace copy from fast clock"}',
    12000, false, 'fast-clock-device', auth.uid()
);

insert into delete_timestamp_test_context(key, value)
select 'workspace_version_before_delete', current_version::text
from public.beekeep_workspace_sync_state
where workspace_id = (select value::uuid from delete_timestamp_test_context where key = 'workspace_id');

update public.beekeep_workspace_documents
set payload = '{"id":99622,"deleted":true}',
    updated_at = 2000,
    deleted = true,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from delete_timestamp_test_context where key = 'workspace_id')
  and entity_type = 'delete_clock_test'
  and entity_id = 99622;

do $workspace_delete_assertion$
declare
    row_deleted boolean;
    row_timestamp bigint;
    observed_version bigint;
    previous_version bigint;
begin
    select deleted, updated_at into row_deleted, row_timestamp
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from delete_timestamp_test_context where key = 'workspace_id')
      and entity_type = 'delete_clock_test'
      and entity_id = 99622;
    select s.current_version into observed_version
    from public.beekeep_workspace_sync_state as s
    where s.workspace_id = (select value::uuid from delete_timestamp_test_context where key = 'workspace_id');
    select value::bigint into previous_version
    from delete_timestamp_test_context
    where key = 'workspace_version_before_delete';

    if row_deleted is distinct from true or row_timestamp <= 12000 then
        raise exception 'An explicit workspace deletion was discarded or not advanced past the cloud row';
    end if;
    if observed_version <= previous_version then
        raise exception 'The accepted workspace tombstone did not advance incremental sync state';
    end if;
end;
$workspace_delete_assertion$;

rollback;
select 'Explicit deletion timestamp reconciliation tests passed' as result;
