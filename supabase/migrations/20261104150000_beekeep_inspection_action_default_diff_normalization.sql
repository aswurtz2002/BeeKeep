-- Compare a new inspection with the hive's previous inspection and suppress default-only noise.
-- Inspection rows are append-only, so a new inspection must be compared with the latest earlier
-- inspection for the same hive rather than treating every populated field as a change.

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
    prior_inspection_payload jsonb;
    has_prior_inspection boolean := false;
    details text[] := array[]::text[];
    field_key text;
    field_label text;
    old_value text;
    new_value text;
    old_compare text;
    new_compare text;
    old_display text;
    new_display text;
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

    -- Each saved inspection is a new history row. Use the most recently saved inspection
    -- for this hive as the baseline, excluding the row currently being logged.
    if is_created and hive_id is not null then
        select d.payload::jsonb
          into prior_inspection_payload
          from public.beekeep_workspace_documents d
         where d.workspace_id = target_workspace_id
           and d.entity_type = 'inspection'
           and d.entity_id <> target_inspection_id
           and not d.deleted
           and nullif(d.payload::jsonb ->> 'hive_id', '')::bigint = hive_id
         order by d.updated_at desc, d.entity_id desc
         limit 1;

        if prior_inspection_payload is not null then
            previous_payload := prior_inspection_payload;
            has_prior_inspection := true;
        end if;
    end if;

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
        old_display := old_value;
        new_display := new_value;

        if field_key = 'strength' then
            old_compare := coalesce(nullif(pg_catalog.btrim(old_value), ''), '');
            new_compare := coalesce(nullif(pg_catalog.btrim(new_value), ''), '');
            if old_compare = '0' then old_compare := ''; end if;
            if new_compare = '0' then new_compare := ''; end if;
            old_display := case old_compare
                when '2' then 'Very Weak'
                when '4' then 'Weak'
                when '6' then 'Moderate'
                when '8' then 'Strong'
                when '10' then 'Very Strong'
                when '' then 'Not set'
                else old_compare
            end;
            new_display := case new_compare
                when '2' then 'Very Weak'
                when '4' then 'Weak'
                when '6' then 'Moderate'
                when '8' then 'Strong'
                when '10' then 'Very Strong'
                when '' then 'Not set'
                else new_compare
            end;
        elsif field_key = 'queen_status' then
            old_display := coalesce(nullif(pg_catalog.btrim(old_value), ''), 'Laying');
            new_display := coalesce(nullif(pg_catalog.btrim(new_value), ''), 'Laying');
            old_compare := pg_catalog.lower(old_display);
            new_compare := pg_catalog.lower(new_display);
        elsif field_key = 'mite_count' then
            old_compare := coalesce(nullif(pg_catalog.btrim(old_value), ''), '0')::numeric::text;
            new_compare := coalesce(nullif(pg_catalog.btrim(new_value), ''), '0')::numeric::text;
            old_display := old_compare;
            new_display := new_compare;
        elsif field_key = 'sample_size' then
            old_compare := coalesce(nullif(pg_catalog.btrim(old_value), ''), '300')::numeric::text;
            new_compare := coalesce(nullif(pg_catalog.btrim(new_value), ''), '300')::numeric::text;
            old_display := old_compare;
            new_display := new_compare;
        elsif field_key in (
            'eggs','open_brood','capped_brood','honey_stores','pollen',
            'empty_drawn_comb','emergency_cells','supercedure_cells','swarm_cells',
            'bee_count_frames'
        ) then
            old_compare := coalesce(nullif(pg_catalog.btrim(old_value), ''), '0')::numeric::text;
            new_compare := coalesce(nullif(pg_catalog.btrim(new_value), ''), '0')::numeric::text;
            old_display := old_compare;
            new_display := new_compare;
        elsif field_key = 'disease_flags' then
            old_compare := coalesce(nullif(pg_catalog.btrim(old_value), ''), '');
            new_compare := coalesce(nullif(pg_catalog.btrim(new_value), ''), '');
            old_display := coalesce(nullif(pg_catalog.btrim(old_value), ''), 'None');
            new_display := coalesce(nullif(pg_catalog.btrim(new_value), ''), 'None');
        elsif field_key = 'notes' then
            old_compare := coalesce(pg_catalog.btrim(old_value), '');
            new_compare := coalesce(pg_catalog.btrim(new_value), '');
        else
            old_compare := old_value;
            new_compare := new_value;
        end if;

        if is_created and not has_prior_inspection then
            -- On the first inspection, describe recorded observations but omit blank/default
            -- form values so defaults such as 0 mites and a 300-bee sample aren't presented
            -- as deliberate changes.
            if field_key = 'strength' then
                if new_compare <> '' then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_display);
                end if;
            elsif field_key = 'queen_status' then
                if new_compare <> 'laying' then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_display);
                end if;
            elsif field_key = 'mite_count' then
                if new_compare::numeric > 0 then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_display);
                end if;
            elsif field_key = 'sample_size' then
                if coalesce(nullif(pg_catalog.btrim(current_payload ->> 'mite_count'), ''), '0')::numeric > 0 then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_display);
                end if;
            elsif field_key = 'disease_flags' then
                if new_compare <> '' then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_display);
                end if;
            elsif field_key = 'notes' then
                if new_compare <> '' then
                    details := pg_catalog.array_append(details, 'Field notes added');
                end if;
            else
                if new_compare::numeric > 0 then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_display);
                end if;
            end if;
        elsif old_compare is distinct from new_compare then
            if field_key = 'notes' then
                details := pg_catalog.array_append(details, 'Field notes changed');
            else
                details := pg_catalog.array_append(
                    details,
                    field_label || ': ' || old_display || ' → ' || new_display
                );
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
