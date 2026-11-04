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
    inspection_a_id bigint := 900000000000000031;
    inspection_b_id bigint := 900000000000000032;
    inspection_a_action_id uuid := pg_catalog.gen_random_uuid();
    inspection_b_action_id uuid := pg_catalog.gen_random_uuid();
    treatment_batch_id uuid := pg_catalog.gen_random_uuid();
    harvest_batch_id uuid := pg_catalog.gen_random_uuid();
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

    -- First inspection suppresses untouched defaults; a later inspection compares
    -- against the previous inspection for the same hive.
    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at,
        deleted, device_id, client_action_id, action_at
    ) values (
        workspace_to_test, 'inspection', inspection_a_id,
        pg_catalog.jsonb_build_object(
            'id', inspection_a_id, 'hive_id', hive_b_id,
            'strength', 8, 'queen_status', 'Laying',
            'mite_count', 0, 'sample_size', 300, 'eggs', 0,
            'open_brood', 0, 'capped_brood', 0, 'notes', ''
        )::text,
        now_ms + 3, false, 'structured-action-test', inspection_a_action_id, now_ms + 3
    );

    if not exists (
        select 1 from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test
           and client_action_id = inspection_a_action_id
           and action_type = 'inspection_created'
           and changes = '[{"field":"strength","old_value":null,"new_value":8}]'::jsonb
    ) then
        raise exception 'First inspection did not suppress default-only fields.';
    end if;

    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at,
        deleted, device_id, client_action_id, action_at
    ) values (
        workspace_to_test, 'inspection', inspection_b_id,
        pg_catalog.jsonb_build_object(
            'id', inspection_b_id, 'hive_id', hive_b_id,
            'strength', 10, 'queen_status', 'Laying',
            'mite_count', 2, 'sample_size', 300, 'eggs', 4,
            'open_brood', 3, 'capped_brood', 0, 'notes', ''
        )::text,
        now_ms + 4, false, 'structured-action-test', inspection_b_action_id, now_ms + 4
    );

    if not exists (
        select 1 from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test
           and client_action_id = inspection_b_action_id
           and changes @> '[
               {"field":"strength","old_value":8,"new_value":10},
               {"field":"mite_count","old_value":0,"new_value":2},
               {"field":"eggs","old_value":0,"new_value":4}
           ]'::jsonb
    ) then
        raise exception 'Inspection diff was not compared against the previous inspection.';
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

    -- Mass Treatment and Harvest are grouped identically: one log row per batch.
    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at,
        deleted, device_id, client_action_id, action_at
    ) values
    (
        workspace_to_test, 'treatment', base_id + 41,
        pg_catalog.jsonb_build_object(
            'id', base_id + 41, 'hive_id', hive_b_id, 'apiary_id', apiary_a_id,
            'batch_id', treatment_batch_id, 'treatment_type', 'formic',
            'product', 'Formic Pro', 'inserted_at', '2026-11-04'
        )::text,
        now_ms + 5, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms + 5
    ),
    (
        workspace_to_test, 'treatment', base_id + 42,
        pg_catalog.jsonb_build_object(
            'id', base_id + 42, 'hive_id', hive_d_id, 'apiary_id', apiary_a_id,
            'batch_id', treatment_batch_id, 'treatment_type', 'formic',
            'product', 'Formic Pro', 'inserted_at', '2026-11-04'
        )::text,
        now_ms + 5, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms + 5
    );

    if (select pg_catalog.count(*) from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test and client_action_id = treatment_batch_id) <> 1
       or not exists (
           select 1 from public.beekeep_workspace_action_log
            where workspace_id = workspace_to_test
              and client_action_id = treatment_batch_id
              and action_type = 'mass_treatment'
              and affected_hive_count = 2
              and changes @> '[{"field":"product","old_value":null,"new_value":"Formic Pro"}]'::jsonb
       ) then
        raise exception 'Mass Treatment did not produce one structured batch entry.';
    end if;

    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at,
        deleted, device_id, client_action_id, action_at
    ) values
    (
        workspace_to_test, 'harvest', base_id + 51,
        pg_catalog.jsonb_build_object(
            'id', base_id + 51, 'hive_id', hive_b_id, 'apiary_id', apiary_a_id,
            'batch_id', harvest_batch_id, 'supers_pulled', 2,
            'wet_honey_weight', 18.5, 'dry_honey_weight', 15.0, 'weight_unit', 'lb'
        )::text,
        now_ms + 6, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms + 6
    ),
    (
        workspace_to_test, 'harvest', base_id + 52,
        pg_catalog.jsonb_build_object(
            'id', base_id + 52, 'hive_id', hive_d_id, 'apiary_id', apiary_a_id,
            'batch_id', harvest_batch_id, 'supers_pulled', 2,
            'wet_honey_weight', 18.5, 'dry_honey_weight', 15.0, 'weight_unit', 'lb'
        )::text,
        now_ms + 6, false, 'structured-action-test', pg_catalog.gen_random_uuid(), now_ms + 6
    );

    if (select pg_catalog.count(*) from public.beekeep_workspace_action_log
         where workspace_id = workspace_to_test and client_action_id = harvest_batch_id) <> 1
       or not exists (
           select 1 from public.beekeep_workspace_action_log
            where workspace_id = workspace_to_test
              and client_action_id = harvest_batch_id
              and action_type = 'mass_harvest'
              and affected_hive_count = 2
              and changes @> '[{"field":"wet_honey_weight","old_value":null,"new_value":18.5}]'::jsonb
       ) then
        raise exception 'Mass Harvest did not produce one structured batch entry.';
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
