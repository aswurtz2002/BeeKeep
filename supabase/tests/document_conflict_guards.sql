-- Verify cloud documents cannot move backwards or resurrect permanent records.
-- Draft-clear tombstones remain reusable because future drafts use the hive ID again.
begin;

create temporary table document_conflict_guard_context (
    key text primary key,
    value text not null
);
grant select, insert, update, delete on document_conflict_guard_context to authenticated;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
values (
    auth.uid(), 'conflict_guard_test', 99611,
    '{"id":99611,"value":"current"}', 2000, false, 'conflict-guard-test'
);

insert into document_conflict_guard_context(key, value)
select 'personal_version_after_insert', current_version::text
from public.beekeep_personal_sync_state where user_id = auth.uid();

-- A delayed older update must leave the current payload, timestamp and sync version untouched.
update public.beekeep_documents
set payload = '{"id":99611,"value":"stale"}',
    updated_at = 1999
where user_id = auth.uid()
  and entity_type = 'conflict_guard_test'
  and entity_id = 99611;

do $personal_stale_assertion$
declare
    row_payload text;
    row_timestamp bigint;
    observed_version bigint;
    version_after_insert bigint;
begin
    select payload, updated_at into row_payload, row_timestamp
    from public.beekeep_documents
    where user_id = auth.uid()
      and entity_type = 'conflict_guard_test'
      and entity_id = 99611;
    select s.current_version into observed_version
    from public.beekeep_personal_sync_state as s where s.user_id = auth.uid();
    select value::bigint into version_after_insert
    from document_conflict_guard_context where key = 'personal_version_after_insert';
    if row_payload <> '{"id":99611,"value":"current"}' or row_timestamp <> 2000 then
        raise exception 'A stale personal write replaced a newer cloud row';
    end if;
    if observed_version <> version_after_insert then
        raise exception 'A rejected stale personal write advanced the sync version';
    end if;
end;
$personal_stale_assertion$;

-- Equal timestamps are accepted; the last server commit deterministically wins.
update public.beekeep_documents
set payload = '{"id":99611,"value":"equal-time-later-commit"}',
    updated_at = 2000
where user_id = auth.uid()
  and entity_type = 'conflict_guard_test'
  and entity_id = 99611;

-- A newer tombstone is accepted and cannot be changed back into a live record.
update public.beekeep_documents
set payload = '{"id":99611,"deleted":true}',
    updated_at = 2001,
    deleted = true
where user_id = auth.uid()
  and entity_type = 'conflict_guard_test'
  and entity_id = 99611;

update public.beekeep_documents
set payload = '{"id":99611,"value":"resurrected"}',
    updated_at = 2002,
    deleted = false
where user_id = auth.uid()
  and entity_type = 'conflict_guard_test'
  and entity_id = 99611;

do $personal_tombstone_assertion$
declare
    row_deleted boolean;
    row_payload text;
begin
    select deleted, payload into row_deleted, row_payload
    from public.beekeep_documents
    where user_id = auth.uid()
      and entity_type = 'conflict_guard_test'
      and entity_id = 99611;
    if row_deleted is distinct from true or row_payload <> '{"id":99611,"deleted":true}' then
        raise exception 'A permanent personal tombstone was resurrected';
    end if;
end;
$personal_tombstone_assertion$;

-- Draft-clear tombstones are intentionally reusable on later edits to the same hive.
insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
values (
    auth.uid(), 'inspection_note_draft', 99613,
    '{"id":99613,"deleted":true}', 4000, true, 'conflict-guard-test'
);
update public.beekeep_documents
set payload = '{"id":99613,"notes":"A later draft"}',
    updated_at = 4001,
    deleted = false
where user_id = auth.uid()
  and entity_type = 'inspection_note_draft'
  and entity_id = 99613;

do $personal_draft_assertion$
declare
    row_deleted boolean;
    row_payload text;
begin
    select deleted, payload into row_deleted, row_payload
    from public.beekeep_documents
    where user_id = auth.uid()
      and entity_type = 'inspection_note_draft'
      and entity_id = 99613;
    if row_deleted is distinct from false or row_payload <> '{"id":99613,"notes":"A later draft"}' then
        raise exception 'A later inspection note draft could not replace its clear tombstone';
    end if;
end;
$personal_draft_assertion$;

reset role;
update auth.users
set raw_app_meta_data = jsonb_build_object(
    'beekeep_plan', 'ultimate_yearly',
    'beekeep_subscription_expires_at', '2099-01-01T00:00:00Z'
)
where id = '00000000-0000-0000-0000-000000000001';

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

insert into document_conflict_guard_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Conflict Guard Test');

insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    (select value::uuid from document_conflict_guard_context where key = 'workspace_id'),
    'conflict_guard_test', 99612,
    '{"id":99612,"value":"current"}', 3000, false, 'conflict-guard-test', auth.uid()
);

-- A delayed workspace write is rejected as well.
update public.beekeep_workspace_documents
set payload = '{"id":99612,"value":"stale"}',
    updated_at = 2999,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from document_conflict_guard_context where key = 'workspace_id')
  and entity_type = 'conflict_guard_test'
  and entity_id = 99612;

do $workspace_stale_assertion$
declare
    row_payload text;
    row_timestamp bigint;
begin
    select payload, updated_at into row_payload, row_timestamp
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from document_conflict_guard_context where key = 'workspace_id')
      and entity_type = 'conflict_guard_test'
      and entity_id = 99612;
    if row_payload <> '{"id":99612,"value":"current"}' or row_timestamp <> 3000 then
        raise exception 'A stale workspace write replaced a newer cloud row';
    end if;
end;
$workspace_stale_assertion$;

update public.beekeep_workspace_documents
set payload = '{"id":99612,"deleted":true}',
    updated_at = 3001,
    deleted = true,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from document_conflict_guard_context where key = 'workspace_id')
  and entity_type = 'conflict_guard_test'
  and entity_id = 99612;

update public.beekeep_workspace_documents
set payload = '{"id":99612,"value":"resurrected"}',
    updated_at = 3002,
    deleted = false,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from document_conflict_guard_context where key = 'workspace_id')
  and entity_type = 'conflict_guard_test'
  and entity_id = 99612;

do $workspace_tombstone_assertion$
declare
    row_deleted boolean;
    row_payload text;
begin
    select deleted, payload into row_deleted, row_payload
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from document_conflict_guard_context where key = 'workspace_id')
      and entity_type = 'conflict_guard_test'
      and entity_id = 99612;
    if row_deleted is distinct from true or row_payload <> '{"id":99612,"deleted":true}' then
        raise exception 'A permanent workspace tombstone was resurrected';
    end if;
end;
$workspace_tombstone_assertion$;

rollback;
select 'Cloud document conflict guard tests passed' as result;
