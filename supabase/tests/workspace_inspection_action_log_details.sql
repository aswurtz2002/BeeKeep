-- Focused regression coverage for human-readable inspection activity descriptions.
-- No persistent rows are created; the helper falls back to the supplied hive ID.
begin;

do $inspection_action_description_test$
declare
    created_description text;
    edited_description text;
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
end;
$inspection_action_description_test$;

rollback;

select 'Inspection action description tests passed' as result;
