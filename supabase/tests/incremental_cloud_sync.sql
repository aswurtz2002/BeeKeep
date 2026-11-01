-- Verify server-ordered incremental sync versions, baseline backfill, RLS and tombstones.
-- Run after 20261101143217_beekeep_incremental_sync_versions.sql in the disposable CI database.
begin;

-- Backfill must assign a version to every existing personal and workspace row.
do $backfill_assertions$
begin
    if exists (
        select 1
        from public.beekeep_documents d
        left join public.beekeep_personal_sync_versions v
          on v.user_id = d.user_id
         and v.entity_type = d.entity_type
         and v.entity_id = d.entity_id
        where v.sync_version is null
    ) then
        raise exception 'An existing personal document is missing its sync version';
    end if;

    if exists (
        select 1
        from public.beekeep_workspace_documents d
        left join public.beekeep_workspace_sync_versions v
          on v.workspace_id = d.workspace_id
         and v.entity_type = d.entity_type
         and v.entity_id = d.entity_id
        where v.sync_version is null
    ) then
        raise exception 'An existing workspace document is missing its sync version';
    end if;

    if exists (
        select 1
        from public.beekeep_personal_sync_state s
        where s.current_version < (
            select coalesce(max(v.sync_version), 0)
            from public.beekeep_personal_sync_versions v
            where v.user_id = s.user_id
        )
    ) then
        raise exception 'A personal sync head is behind its version ledger';
    end if;

    if exists (
        select 1
        from public.beekeep_workspace_sync_state s
        where s.current_version < (
            select coalesce(max(v.sync_version), 0)
            from public.beekeep_workspace_sync_versions v
            where v.workspace_id = s.workspace_id
        )
    ) then
        raise exception 'A workspace sync head is behind its version ledger';
    end if;
end;
$backfill_assertions$;

create temporary table incremental_sync_test_context (
    key text primary key,
    value text not null
);
grant select, insert, update, delete on incremental_sync_test_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

-- Existing personal data is available through the new page RPC.
insert into incremental_sync_test_context(key, value)
select 'personal_head_before', head_version::text
from public.beekeep_personal_sync_head();

do $personal_baseline_assertions$
declare
    current_head bigint;
    existing_count integer;
begin
    select value::bigint into current_head
    from incremental_sync_test_context
    where key = 'personal_head_before';

    select count(*) into existing_count
    from public.beekeep_personal_sync_changes(0, current_head, 500)
    where entity_type = 'hive' and entity_id = 1;

    if existing_count <> 1 then
        raise exception 'First incremental personal pull did not return the existing seeded hive';
    end if;
end;
$personal_baseline_assertions$;

-- New personal writes and updates must advance the server-side version, regardless of
-- client timestamps. The latest row is returned with its deletion tombstone intact.
insert into incremental_sync_test_context(key, value)
select 'personal_head_initial', head_version::text
from public.beekeep_personal_sync_head();

insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
values (
    auth.uid(), 'event', 99001,
    '{"id":99001,"title":"Incremental personal event"}',
    1000, false, 'incremental-sync-test'
);

insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
values (
    auth.uid(), 'inspection_note_draft', 99004,
    '{"id":99004,"hive_id":99004,"notes":"Personal draft from device A","recorded_at":1000,"updated_at":1000}',
    1000, false, 'incremental-sync-test'
);

insert into incremental_sync_test_context(key, value)
select 'personal_head_insert', head_version::text
from public.beekeep_personal_sync_head();

update public.beekeep_documents
set payload = '{"id":99001,"title":"Updated incremental personal event"}',
    updated_at = 1001,
    deleted = true
where user_id = auth.uid()
  and entity_type = 'event'
  and entity_id = 99001;

insert into incremental_sync_test_context(key, value)
select 'personal_head_update', head_version::text
from public.beekeep_personal_sync_head();

do $personal_delta_assertions$
declare
    initial_head bigint;
    inserted_head bigint;
    updated_head bigint;
    delta_count integer;
    is_deleted boolean;
    returned_version bigint;
begin
    select value::bigint into initial_head from incremental_sync_test_context where key = 'personal_head_initial';
    select value::bigint into inserted_head from incremental_sync_test_context where key = 'personal_head_insert';
    select value::bigint into updated_head from incremental_sync_test_context where key = 'personal_head_update';

    if inserted_head <= initial_head or updated_head <= inserted_head then
        raise exception 'Personal server sync versions did not advance monotonically';
    end if;

    select count(*), bool_or(deleted), max(sync_version)
      into delta_count, is_deleted, returned_version
    from public.beekeep_personal_sync_changes(initial_head, updated_head, 500)
    where entity_type = 'event' and entity_id = 99001;

    if delta_count <> 1 or is_deleted is distinct from true or returned_version <> updated_head then
        raise exception 'Incremental personal pull did not return the latest tombstone/version';
    end if;

    if exists (
        select 1
        from public.beekeep_personal_sync_changes(updated_head, updated_head, 500)
        where entity_type = 'event' and entity_id = 99001
    ) then
        raise exception 'An unchanged personal cursor returned duplicate rows';
    end if;
end;
$personal_delta_assertions$;

-- A different signed-in user cannot see the first user's personal sync head or changes.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
do $personal_isolation_assertions$
declare
    visible_count integer;
    head_value bigint;
begin
    select head_version into head_value from public.beekeep_personal_sync_head();
    if head_value <> (
        select current_version
        from public.beekeep_personal_sync_state
        where user_id = auth.uid()
    ) then
        raise exception 'Personal sync head did not resolve for the current user';
    end if;

    select count(*) into visible_count
    from public.beekeep_personal_sync_changes(0, 1000000, 500)
    where entity_type = 'event' and entity_id = 99001;
    if visible_count <> 0 then
        raise exception 'Another user''s personal sync changes were exposed';
    end if;

    select count(*) into visible_count
    from public.beekeep_personal_sync_changes(0, 1000000, 500)
    where entity_type = 'inspection_note_draft' and entity_id = 99004;
    if visible_count <> 0 then
        raise exception 'Another user''s personal inspection note draft was exposed';
    end if;
end;
$personal_isolation_assertions$;

-- Team-workspace creation is Ultimate-gated. Give the disposable fixture account
-- a temporary trusted entitlement inside this test transaction; ROLLBACK restores it.
reset role;
update auth.users
set raw_app_meta_data = jsonb_build_object(
    'beekeep_plan', 'ultimate_yearly',
    'beekeep_subscription_expires_at', '2099-01-01T00:00:00Z'
)
where id = '00000000-0000-0000-0000-000000000001';

-- Create a private team workspace as the owner, then verify insert/update/tombstone deltas.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
insert into incremental_sync_test_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Incremental Sync Test');

insert into incremental_sync_test_context(key, value)
select 'workspace_head_initial', head_version::text
from public.beekeep_workspace_sync_head(
    (select value::uuid from incremental_sync_test_context where key = 'workspace_id')
);

insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    (select value::uuid from incremental_sync_test_context where key = 'workspace_id'),
    'event', 99002,
    '{"id":99002,"title":"Incremental workspace event"}',
    1000, false, 'incremental-sync-test', auth.uid()
);

insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    (select value::uuid from incremental_sync_test_context where key = 'workspace_id'),
    'inspection_note_draft', 99003,
    '{"id":99003,"hive_id":99003,"notes":"Draft created on device A","recorded_at":1000,"updated_at":1000}',
    1000, false, 'incremental-sync-test', auth.uid()
);

insert into incremental_sync_test_context(key, value)
select 'workspace_head_insert', head_version::text
from public.beekeep_workspace_sync_head(
    (select value::uuid from incremental_sync_test_context where key = 'workspace_id')
);

update public.beekeep_workspace_documents
set payload = '{"id":99002,"title":"Updated incremental workspace event"}',
    updated_at = 1001,
    deleted = true,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from incremental_sync_test_context where key = 'workspace_id')
  and entity_type = 'event'
  and entity_id = 99002;

update public.beekeep_workspace_documents
set payload = '{"id":99003,"hive_id":99003,"notes":"Updated from device B","recorded_at":1000,"updated_at":1002}',
    updated_at = 1002,
    deleted = false,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from incremental_sync_test_context where key = 'workspace_id')
  and entity_type = 'inspection_note_draft'
  and entity_id = 99003;

insert into incremental_sync_test_context(key, value)
select 'workspace_head_update', head_version::text
from public.beekeep_workspace_sync_head(
    (select value::uuid from incremental_sync_test_context where key = 'workspace_id')
);

do $workspace_delta_assertions$
declare
    workspace_id_value uuid;
    initial_head bigint;
    inserted_head bigint;
    updated_head bigint;
    delta_count integer;
    is_deleted boolean;
    returned_version bigint;
    note_count integer;
    note_is_deleted boolean;
    note_version bigint;
    note_text text;
begin
    select value::uuid into workspace_id_value from incremental_sync_test_context where key = 'workspace_id';
    select value::bigint into initial_head from incremental_sync_test_context where key = 'workspace_head_initial';
    select value::bigint into inserted_head from incremental_sync_test_context where key = 'workspace_head_insert';
    select value::bigint into updated_head from incremental_sync_test_context where key = 'workspace_head_update';

    if inserted_head <= initial_head or updated_head <= inserted_head then
        raise exception 'Workspace server sync versions did not advance monotonically';
    end if;

    select count(*), bool_or(deleted), max(sync_version)
      into delta_count, is_deleted, returned_version
    from public.beekeep_workspace_sync_changes(
        workspace_id_value, initial_head, updated_head, 500
    )
    where entity_type = 'event' and entity_id = 99002;

    if delta_count <> 1 or is_deleted is distinct from true or returned_version >= updated_head then
        raise exception 'Incremental workspace pull did not return the latest tombstone/version';
    end if;

    select count(*), bool_or(deleted), max(sync_version), max(payload::jsonb ->> 'notes')
      into note_count, note_is_deleted, note_version, note_text
    from public.beekeep_workspace_sync_changes(
        workspace_id_value, initial_head, updated_head, 500
    )
    where entity_type = 'inspection_note_draft' and entity_id = 99003;

    if note_count <> 1
       or note_is_deleted is distinct from false
       or note_version <> updated_head
       or note_text <> 'Updated from device B' then
        raise exception 'Incremental workspace pull did not return the latest inspection note draft';
    end if;
end;
$workspace_delta_assertions$;

-- A non-member cannot read the workspace head, ledger rows, or workspace change pages.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
do $workspace_isolation_assertions$
declare
    workspace_id_value uuid;
    head_value bigint;
    visible_count integer;
begin
    select value::uuid into workspace_id_value
    from incremental_sync_test_context where key = 'workspace_id';

    select head_version into head_value
    from public.beekeep_workspace_sync_head(workspace_id_value);
    if head_value <> 0 then
        raise exception 'A non-member could read a private workspace sync head';
    end if;

    select count(*) into visible_count
    from public.beekeep_workspace_sync_changes(workspace_id_value, 0, 1000000, 500);
    if visible_count <> 0 then
        raise exception 'A non-member could read private workspace changes';
    end if;

    select count(*) into visible_count
    from public.beekeep_workspace_sync_versions
    where workspace_id = workspace_id_value;
    if visible_count <> 0 then
        raise exception 'RLS did not hide another workspace sync ledger';
    end if;
end;
$workspace_isolation_assertions$;

reset role;

do $rpc_permission_assertions$
begin
    if has_function_privilege('anon', 'public.beekeep_personal_sync_head()', 'EXECUTE')
       or has_function_privilege('anon', 'public.beekeep_workspace_sync_head(uuid)', 'EXECUTE')
       or has_function_privilege('anon', 'public.beekeep_personal_sync_changes(bigint,bigint,integer)', 'EXECUTE')
       or has_function_privilege('anon', 'public.beekeep_workspace_sync_changes(uuid,bigint,bigint,integer)', 'EXECUTE') then
        raise exception 'Anonymous role must not execute incremental sync RPCs';
    end if;

    if not has_function_privilege('authenticated', 'public.beekeep_personal_sync_head()', 'EXECUTE')
       or not has_function_privilege('authenticated', 'public.beekeep_workspace_sync_head(uuid)', 'EXECUTE')
       or not has_function_privilege('authenticated', 'public.beekeep_personal_sync_changes(bigint,bigint,integer)', 'EXECUTE')
       or not has_function_privilege('authenticated', 'public.beekeep_workspace_sync_changes(uuid,bigint,bigint,integer)', 'EXECUTE') then
        raise exception 'Authenticated role must execute incremental sync RPCs';
    end if;
end;
$rpc_permission_assertions$;

rollback;
select 'Incremental cloud sync tests passed' as result;
