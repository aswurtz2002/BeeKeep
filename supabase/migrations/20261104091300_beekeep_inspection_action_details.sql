-- Describe inspection creation and edits using the actual inspection fields.
-- Existing log rows are not rewritten; future activity entries become descriptive.


-- Inspection activity descriptions compare the persisted snapshots. The log contains
-- field names and concise values, never the entire inspection note text.
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
        'strength','queenStatus','miteCount','sampleSize','eggs','openBrood',
        'cappedBrood','honeyStores','pollen','emptyDrawnComb','emergencyCells',
        'supercedureCells','swarmCells','diseaseFlags','beeCountFrames','notes'
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
            when 'queenStatus' then 'Queen status'
            when 'miteCount' then 'Mite count'
            when 'sampleSize' then 'Sample size'
            when 'eggs' then 'Eggs'
            when 'openBrood' then 'Open brood'
            when 'cappedBrood' then 'Capped brood'
            when 'honeyStores' then 'Honey stores'
            when 'pollen' then 'Pollen'
            when 'emptyDrawnComb' then 'Empty drawn comb'
            when 'emergencyCells' then 'Emergency cells'
            when 'supercedureCells' then 'Supersedure cells'
            when 'swarmCells' then 'Swarm cells'
            when 'diseaseFlags' then 'Disease flags'
            when 'beeCountFrames' then 'Bee count'
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
            elsif field_key = 'diseaseFlags' then
                if pg_catalog.btrim(new_value) <> '' then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_value);
                end if;
            elsif field_key in ('strength','queenStatus','miteCount','sampleSize') then
                if new_value <> '' then
                    details := pg_catalog.array_append(details, field_label || ': ' || new_value);
                end if;
            elsif new_value not in ('', '0', '0.0', '0.00') then
                details := pg_catalog.array_append(details, field_label || ': ' || new_value);
            end if;
        elsif (previous_payload -> field_key) is distinct from (current_payload -> field_key) then
            if field_key = 'notes' then
                details := pg_catalog.array_append(details, 'Field notes changed');
            elsif field_key = 'diseaseFlags' then
                details := pg_catalog.array_append(details, field_label || ': ' ||
                    coalesce(nullif(old_value, ''), 'None') || ' → ' ||
                    coalesce(nullif(new_value, ''), 'None'));
            else
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


create or replace function public.beekeep_workspace_action_log_after_update()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    insert into public.beekeep_workspace_action_log (
        workspace_id, client_action_id, user_id, username,
        action_type, target_type, target_id, description, action_at
    )
    select
        n.workspace_id,
        coalesce(n.client_action_id, pg_catalog.gen_random_uuid()),
        coalesce(n.updated_by, (select auth.uid())),
        coalesce(nullif(pg_catalog.btrim(p.username), ''), 'Team member'),
        case
            when n.entity_type = 'apiary' and n.deleted and not o.deleted then 'apiary_deleted'
            when n.entity_type = 'apiary' then 'apiary_edited'
            when n.entity_type = 'hive' and n.deleted and not o.deleted then 'hive_deleted'
            when n.entity_type = 'hive' then 'hive_edited'
            else 'inspection_edited'
        end,
        n.entity_type,
        n.entity_id::text,
        case
            when n.entity_type = 'inspection' then
                public.beekeep_inspection_action_log_description(
                    n.workspace_id, n.entity_id, o.payload::jsonb, n.payload::jsonb, false
                )
            else public.beekeep_workspace_action_log_description(
                n.workspace_id,
                n.entity_type,
                n.entity_id,
                case when n.deleted and not o.deleted then o.payload::jsonb else n.payload::jsonb end,
                case
                    when n.entity_type in ('apiary', 'hive') and n.deleted and not o.deleted then 'Deleted'
                    when n.entity_type in ('apiary', 'hive') then 'Edited'
                    else 'Edited'
                end
            )
        end,
        pg_catalog.to_timestamp(
            coalesce(nullif(n.action_at, 0), n.updated_at)::double precision / 1000.0
        )
    from new_rows n
    join old_rows o
      on o.workspace_id = n.workspace_id
     and o.entity_type = n.entity_type
     and o.entity_id = n.entity_id
    left join public.beekeep_profiles p
      on p.user_id = coalesce(n.updated_by, (select auth.uid()))
    where n.entity_type in ('apiary', 'hive', 'inspection')
      and (
          n.payload is distinct from o.payload
          or n.deleted is distinct from o.deleted
      )
      and (n.entity_type <> 'inspection' or not n.deleted)
    on conflict (workspace_id, client_action_id) do nothing;

    -- Individual records are logged on edits; batch rows remain represented
    -- by their single mass-action entry and are intentionally excluded here.
    insert into public.beekeep_workspace_action_log (
        workspace_id, client_action_id, user_id, username,
        action_type, target_type, target_id, description, action_at
    )
    select
        n.workspace_id,
        coalesce(n.client_action_id, pg_catalog.gen_random_uuid()),
        coalesce(n.updated_by, (select auth.uid())),
        coalesce(nullif(pg_catalog.btrim(p.username), ''), 'Team member'),
        case n.entity_type
            when 'feeding' then 'feeding_edited'
            when 'treatment' then 'treatment_edited'
            else 'harvest_edited'
        end,
        n.entity_type,
        n.entity_id::text,
        public.beekeep_workspace_action_log_description(
            n.workspace_id, n.entity_type, n.entity_id, n.payload::jsonb, 'Edited'
        ),
        pg_catalog.to_timestamp(
            coalesce(nullif(n.action_at, 0), n.updated_at)::double precision / 1000.0
        )
    from new_rows n
    join old_rows o
      on o.workspace_id = n.workspace_id
     and o.entity_type = n.entity_type
     and o.entity_id = n.entity_id
    left join public.beekeep_profiles p
      on p.user_id = coalesce(n.updated_by, (select auth.uid()))
    where n.entity_type in ('feeding', 'treatment', 'harvest')
      and nullif(n.payload::jsonb ->> 'batch_id', '') is null
      and (
          n.payload is distinct from o.payload
          or n.deleted is distinct from o.deleted
      )
    on conflict (workspace_id, client_action_id) do nothing;

    return null;
end;
$function$;


