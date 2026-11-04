-- Manual regression tests for the structured Workspace Actions database stage.
-- Run against a staging/dev database containing at least one team workspace.
-- All temporary business documents and resulting action-log rows are rolled back.

begin;

do $test$
declare
    workspace_to_test uuid;
    base_id bigint := 900000000000000000;
    now_ms bigint := (extract(epoch from clock_timestamp()) * 1000)::bigint;
    apiary_a_id bigint := 900000000000000001;
    apiary_b_id bigint := 900000000000000002;
    hive_a_id bigint := 900000000000000011;
    hive_b_id bigint := 900000000000000012;
    hive_c_id bigint := 900000000000000013;
    hive_d_id bigint := 900000000000000014;
    move_action_id uuid := gen_random_uuid();
    noop_action_id uuid := gen_random_uuid();
    delete_action_id uuid := gen_random_uuid();
    batch_id_value uuid := gen_random_uuid();
    legacy_count_before bigint;
    legacy_count_after bigint;
    result_row record;
begin
    select id into workspace_to_test
      from public.beekeep_workspaces
     where is_personal = false
     order by id
     limit 1;

    if workspace_to_test is null then
        raise exception 'This regression script requires a staging/dev team workspace.';
    end if;

    select pg_catalog.count(*)
      into legacy_count_before
      from public.beekeep_workspace_action_log
     where workspace_id = workspace_to_test
       and structured_version = 0;

    -- Action-time apiary and hive names for snapshot and movement tests.
    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at,
        deleted, device_id, client_action_id, action_at
    ) values
    (
        workspace_to_test, 'apiary', apiary_a_id,
        pg_catalog.jsonb_build_object('id', apiary_a_id, 'name', 'Audit Test Apiary A')::text,
        now_ms, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms
    ),
    (
        workspace_to_test, 'apiary', apiary_b_id,
        pg_catalog.jsonb_build_object('id', apiary_b_id, 'name', 'Audit Test Apiary B')::text,
        now_ms, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms
    );

    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at,
        deleted, device_id, client_action_id, action_at
    ) values
    (
        workspace_to_test, 'hive', hive_a_id,
        pg_catalog.jsonb_build_object(
            'id', hive_a_id, 'number', '1', 'apiary_id', apiary_a_id,
            'apiary', 'Audit Test Apiary A', 'strength', 6,
            'tag_uid', 'DO_NOT_AUDIT_TAG', 'latitude', 52.1, 'longitude', -105.1
        )::text,
        now_ms, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms
    ),
    (
        workspace_to_test, 'hive', hive_b_id,
        pg_catalog.jsonb_build_object(
            'id', hive_b_id, 'number', '2', 'apiary_id', apiary_a_id,
            'apiary', 'Audit Test Apiary A', 'strength', 6
        )::text,
        now_ms, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms
    ),
    (
        workspace_to_test, 'hive', hive_c_id,
        pg_catalog.jsonb_build_object(
            'id', hive_c_id, 'number', '3', 'apiary_id', apiary_a_id,
            'apiary', 'Audit Test Apiary A', 'strength', 6
        )::text,
        now_ms, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms
    ),
    (
        workspace_to_test, 'hive', hive_d_id,
        pg_catalog.jsonb_build_object(
            'id', hive_d_id, 'number', '4', 'apiary_id', apiary_a_id,
            'apiary', 'Audit Test Apiary A', 'strength', 6
        )::text,
        now_ms, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms
    );

    if not exists (
        select 1 from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test
           and target_id = hive_a_id::text
           and structured_version = 1
           and apiary_name_snapshot = 'Audit Test Apiary A'
           and hive_name_snapshot = '1'
           and changes @> '[{"field":"strength","old_value":null,"new_value":6}]'::jsonb
    ) then
        raise exception 'Hive insert did not create the expected values and action-time snapshots.';
    end if;

    if exists (
        select 1 from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test
           and target_id = hive_a_id::text
           and (changes::text ilike '%tag_uid%' or changes::text ilike '%latitude%' or changes::text ilike '%longitude%')
    ) then
        raise exception 'Sensitive hive metadata was included in the structured change list.';
    end if;

    -- A hive move must preserve both Apiary names, rather than only numeric IDs.
    update public.beekeep_workspace_documents
       set payload = pg_catalog.jsonb_set(
               pg_catalog.jsonb_set(payload::jsonb, '{apiary_id}', pg_catalog.to_jsonb(apiary_b_id)),
               '{apiary}', pg_catalog.to_jsonb('Audit Test Apiary B'::text)
           )::text,
           updated_at = now_ms + 1,
           client_action_id = move_action_id,
           action_at = now_ms + 1
     where workspace_id = workspace_to_test
       and entity_type = 'hive'
       and entity_id = hive_a_id;

    select changes into result_row
      from public.beekeep_workspace_action_log
     where workspace_id = workspace_to_test
       and client_action_id = move_action_id;

    if result_row.changes is null
       or not result_row.changes @> '[{"field":"apiary","old_value":"Audit Test Apiary A","new_value":"Audit Test Apiary B"}]'::jsonb then
        raise exception 'Hive move did not store the old and new Apiary names.';
    end if;

    -- Metadata-only updates are not meaningful action-log entries.
    update public.beekeep_workspace_documents
       set payload = (payload::jsonb || pg_catalog.jsonb_build_object('updated_at', now_ms + 2))::text,
           updated_at = now_ms + 2,
           client_action_id = noop_action_id,
           action_at = now_ms + 2
     where workspace_id = workspace_to_test
       and entity_type = 'hive'
       and entity_id = hive_b_id;

    if exists (
        select 1 from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test
           and client_action_id = noop_action_id
    ) then
        raise exception 'Metadata-only/no-op update created an action-log entry.';
    end if;

    -- Deleted hive values remain in the old_value side of the structured diff.
    update public.beekeep_workspace_documents
       set deleted = true,
           updated_at = now_ms + 3,
           client_action_id = delete_action_id,
           action_at = now_ms + 3
     where workspace_id = workspace_to_test
       and entity_type = 'hive'
       and entity_id = hive_c_id;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test
           and client_action_id = delete_action_id
           and action_type = 'hive_deleted'
           and changes @> '[{"field":"number","old_value":"3","new_value":null}]'::jsonb
    ) then
        raise exception 'Hive delete did not retain key values as old-to-empty changes.';
    end if;

    -- One Apiary-wide batch must be one entry, with the exact hive list and values.
    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at,
        deleted, device_id, client_action_id, action_at
    ) values
    (
        workspace_to_test, 'feeding', base_id + 21,
        pg_catalog.jsonb_build_object(
            'id', base_id + 21, 'hive_id', hive_b_id, 'apiary_id', apiary_a_id,
            'batch_id', batch_id_value, 'feed_type', 'syrup', 'amount', 2.5, 'unit', 'L'
        )::text,
        now_ms + 4, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms + 4
    ),
    (
        workspace_to_test, 'feeding', base_id + 22,
        pg_catalog.jsonb_build_object(
            'id', base_id + 22, 'hive_id', hive_d_id, 'apiary_id', apiary_a_id,
            'batch_id', batch_id_value, 'feed_type', 'syrup', 'amount', 2.5, 'unit', 'L'
        )::text,
        now_ms + 4, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms + 4
    );

    select * into result_row
      from public.beekeep_workspace_action_log
     where workspace_id = workspace_to_test
       and client_action_id = batch_id_value;

    if result_row.id is null
       or result_row.structured_version <> 1
       or result_row.action_type <> 'mass_feed'
       or result_row.affected_hive_count <> 2
       or pg_catalog.cardinality(result_row.affected_hive_ids) <> 2
       or result_row.affected_hive_ids <> array[hive_b_id, hive_d_id]::bigint[]
       or pg_catalog.jsonb_array_length(result_row.affected_hives_snapshot) <> 2
       or not result_row.changes @> '[{"field":"amount","old_value":null,"new_value":2.5}]'::jsonb then
        raise exception 'Apiary-wide batch did not produce one structured row with affected hives and values.';
    end if;

    select pg_catalog.count(*)
      into legacy_count_after
      from public.beekeep_workspace_action_log
     where workspace_id = workspace_to_test
       and structured_version = 0;

    if legacy_count_after <> legacy_count_before then
        raise exception 'Existing legacy action-log rows were modified.';
    end if;

    if pg_catalog.to_regclass('public.beekeep_workspace_action_log_created_desc_idx') is null
       or pg_catalog.to_regclass('public.beekeep_workspace_action_log_action_desc_idx') is null then
        raise exception 'A pre-existing action-log index is missing.';
    end if;

    if not exists (
        select 1 from pg_catalog.pg_policies
         where schemaname = 'public'
           and tablename = 'beekeep_workspace_action_log'
           and policyname = 'Only the team owner can read active workspace actions'
    ) then
        raise exception 'The owner-only action-log read policy is missing.';
    end if;
end;
$test$;

rollback;
