-- Focused regression coverage for human-readable inspection activity descriptions.
-- No persistent rows are created; the helper falls back to the supplied hive ID.
begin;

do $inspection_action_description_test$
declare
    created_description text;
    edited_description text;
    default_only_description text;
    default_fill_description text;
begin
    created_description := public.beekeep_inspection_action_log_description(
        '00000000-0000-0000-0000-000000000000'::uuid,
        96303,
        '{}'::jsonb,
        jsonb_build_object(
            'hive_id', 96302,
            'strength', 8,
            'queen_status', 'Spotted',
            'mite_count', 2,
            'sample_size', 300,
            'eggs', 2.0,
            'honey_stores', 4.0,
            'notes', 'private note text'
        ),
        true
    );

    if created_description not like 'Recorded inspection for Hive 96302: Strength: Strong, Queen status: Spotted, Mite count: 2, Sample size: 300%'
       or created_description not like '%Field notes added'
       or position('private note text' in created_description) > 0 then
        raise exception 'Inspection creation description was not accurate or exposed note contents: %', created_description;
    end if;

    edited_description := public.beekeep_inspection_action_log_description(
        '00000000-0000-0000-0000-000000000000'::uuid,
        96303,
        jsonb_build_object(
            'hive_id', 96302,
            'strength', 6,
            'queen_status', 'Laying',
            'mite_count', 2,
            'sample_size', 300,
            'eggs', 2.0,
            'honey_stores', 4.0,
            'notes', 'old private note'
        ),
        jsonb_build_object(
            'hive_id', 96302,
            'strength', 8,
            'queen_status', 'Spotted',
            'mite_count', 4,
            'sample_size', 300,
            'eggs', 2.0,
            'honey_stores', 4.0,
            'notes', 'new private note'
        ),
        false
    );

    if edited_description not like 'Updated inspection for Hive 96302: Strength: Moderate → Strong, Queen status: Laying → Spotted, Mite count: 2 → 4%'
       or edited_description not like '%Field notes changed'
       or position('Eggs:' in edited_description) > 0
       or position('Honey stores:' in edited_description) > 0
       or position('private note' in edited_description) > 0 then
        raise exception 'Inspection edit description did not isolate changed fields: %', edited_description;
    end if;

    default_only_description := public.beekeep_inspection_action_log_description(
        '00000000-0000-0000-0000-000000000000'::uuid,
        96303,
        '{}'::jsonb,
        jsonb_build_object(
            'hive_id', 96302,
            'strength', 4,
            'queen_status', 'Laying',
            'mite_count', 0,
            'sample_size', 300,
            'bee_count_frames', 2,
            'eggs', 0,
            'open_brood', 0,
            'capped_brood', 0,
            'honey_stores', 0,
            'pollen', 0,
            'empty_drawn_comb', 0,
            'emergency_cells', 0,
            'supercedure_cells', 0,
            'swarm_cells', 0,
            'disease_flags', '',
            'notes', ''
        ),
        true
    );

    if default_only_description <> 'Recorded inspection for Hive 96302: Strength: Weak, Bee count: 2' then
        raise exception 'Inspection creation included untouched default values: %', default_only_description;
    end if;

    default_fill_description := public.beekeep_inspection_action_log_description(
        '00000000-0000-0000-0000-000000000000'::uuid,
        96303,
        jsonb_build_object('hive_id', 96302, 'strength', 4),
        jsonb_build_object(
            'hive_id', 96302,
            'strength', 8,
            'queen_status', 'Laying',
            'mite_count', 0,
            'sample_size', 300,
            'eggs', 0,
            'open_brood', 0,
            'capped_brood', 0,
            'honey_stores', 0,
            'pollen', 0,
            'empty_drawn_comb', 0,
            'emergency_cells', 0,
            'supercedure_cells', 0,
            'swarm_cells', 0,
            'disease_flags', '',
            'bee_count_frames', 0,
            'notes', ''
        ),
        false
    );

    if default_fill_description <> 'Updated inspection for Hive 96302: Strength: Weak → Strong' then
        raise exception 'Legacy missing defaults were reported as changed fields: %', default_fill_description;
    end if;
end;
$inspection_action_description_test$;

rollback;

select 'Inspection action description tests passed' as result;
