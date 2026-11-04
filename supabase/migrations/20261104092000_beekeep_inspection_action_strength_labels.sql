-- Render the stored strength grade as the field-facing label in action descriptions.

create or replace function public.beekeep_inspection_action_log_description(
    target_workspace_id uuid,
    target_inspection_id bigint,
    previous_payload jsonb,
    current_payload jsonb,
    is_created boolean
)
returns text
language plpgsql
security invoker
set search_path = ''
as $function$
declare
    hive_id bigint;
    hive_name text;
    details text[] := array[]::text[];
    field_key text;
    field_label text;
    old_value text;
    new_value text;
    field_keys text[] := array[
        'strength','queen_status','mite_count','sample_size','eggs','open_brood',
        'capped_brood','honey_stores','pollen','empty_drawn_comb','emergency_cells',
        'supercedure_cells','swarm_cells','disease_flags','bee_count_frames','notes'
    ];
begin
    hive_id := nullif(current_payload ->> 'hive_id', '')::bigint;
    select nullif(pg_catalog.btrim(d.payload::jsonb ->> 'number'), '')
      into hive_name
      from public.beekeep_workspace_documents d
     where d.workspace_id = target_workspace_id
       and d.entity_type = 'hive'
       and d.entity_id = hive_id
       and not d.deleted
     limit 1;
    hive_name := coalesce(hive_name, hive_id::text, target_inspection_id::text);

    foreach field_key in array field_keys loop
        field_label := case field_key
            when 'strength' then 'Strength'
            when 'queen_status' then 'Queen status'
            when 'mite_count' then 'Mite count'
            when 'sample_size' then 'Sample size'
            when 'eggs' then 'Eggs'
            when 'open_brood' then 'Open brood'
            when 'capped_brood' then 'Capped brood'
            when 'honey_stores' then 'Honey stores'
            when 'pollen' then 'Pollen'
            when 'empty_drawn_comb' then 'Empty drawn comb'
            when 'emergency_cells' then 'Emergency cells'
            when 'supercedure_cells' then 'Supersedure cells'
            when 'swarm_cells' then 'Swarm cells'
            when 'disease_flags' then 'Disease flags'
            when 'bee_count_frames' then 'Bee count'
            when 'notes' then 'Field notes'
            else field_key
        end;
        old_value := coalesce(previous_payload ->> field_key, '');
        new_value := coalesce(current_payload ->> field_key, '');

        if is_created then
            if field_key = 'notes' then
                if pg_catalog.btrim(new_value) <> '' then
                    details := pg_catalog.array_append(details, 'Field notes added');
                end if;
            elsif field_key = 'disease_flags' then
                if pg_catalog.btrim(new_value) <> '' then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_value);
                end if;
            elsif field_key in ('strength','queen_status','mite_count','sample_size') then
                if new_value <> '' then
                    if field_key = 'strength' then
                        new_value := case new_value when '2' then 'Very Weak' when '4' then 'Weak' when '6' then 'Moderate' when '8' then 'Strong' when '10' then 'Very Strong' else new_value end;
                    end if;
                    details := pg_catalog.array_append(details, field_label || ': ' || new_value);
                end if;
            elsif new_value not in ('', '0', '0.0', '0.00') then
                details := pg_catalog.array_append(details, field_label || ': ' || new_value);
            end if;
        elsif (previous_payload -> field_key) is distinct from (current_payload -> field_key) then
            if field_key = 'notes' then
                details := pg_catalog.array_append(details, 'Field notes changed');
            elsif field_key = 'disease_flags' then
                details := pg_catalog.array_append(details, field_label || ': ' ||
                    coalesce(nullif(old_value, ''), 'None') || ' → ' ||
                    coalesce(nullif(new_value, ''), 'None'));
            else
                if field_key = 'strength' then
                    old_value := case old_value when '2' then 'Very Weak' when '4' then 'Weak' when '6' then 'Moderate' when '8' then 'Strong' when '10' then 'Very Strong' else old_value end;
                    new_value := case new_value when '2' then 'Very Weak' when '4' then 'Weak' when '6' then 'Moderate' when '8' then 'Strong' when '10' then 'Very Strong' else new_value end;
                end if;
                details := pg_catalog.array_append(details, field_label || ': ' ||
                    coalesce(nullif(old_value, ''), 'Not set') || ' → ' ||
                    coalesce(nullif(new_value, ''), 'Not set'));
            end if;
        end if;
    end loop;

    if is_created then
        return pg_catalog.format(
            'Recorded inspection for Hive %s%s',
            hive_name,
            case when pg_catalog.cardinality(details) > 0
                then ': ' || pg_catalog.array_to_string(details, ', ')
                else ''
            end
        );
    end if;

    if pg_catalog.cardinality(details) = 0 then
        return pg_catalog.format('Updated inspection for Hive %s', hive_name);
    end if;
    return pg_catalog.format(
        'Updated inspection for Hive %s: %s',
        hive_name,
        pg_catalog.array_to_string(details, ', ')
    );
end;
$function$;

revoke all on function public.beekeep_inspection_action_log_description(
    uuid, bigint, jsonb, jsonb, boolean
) from public, anon, authenticated;
