-- Step 2D: commit an entire apiary action as one cloud transaction.
-- The mobile client saves action records and this envelope to its durable Room outbox.
-- This invoker RPC validates the selected apiary/hives and writes every record + event
-- together. Existing per-record document sync remains unchanged for all other actions.

begin;

create or replace function public.beekeep_apply_action_batch(
    target_workspace_id uuid,
    target_apiary_id bigint,
    target_apiary_name text,
    target_batch_id uuid,
    target_action_type text,
    target_documents jsonb
)
returns table (
    saved_records integer,
    already_applied boolean
)
language plpgsql
security invoker
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    expected_entity_type text;
    action_count integer;
    event_count integer;
    document_count integer;
    target_hive_ids bigint[];
    matched_hive_count integer;
    existing_count integer;
    mismatched_existing_count integer;
    effective_hive_limit integer;
    locked_hive_count integer;
    apiary_name text;
begin
    if caller_id is null then
        raise exception 'Sign in before syncing an apiary action.'
            using errcode = '42501';
    end if;

    if target_apiary_id is null or target_apiary_id <= 0
       or target_batch_id is null
       or nullif(pg_catalog.btrim(target_apiary_name), '') is null then
        raise exception 'The apiary action is missing its apiary or batch identity.'
            using errcode = '22023';
    end if;

    expected_entity_type := case pg_catalog.lower(pg_catalog.btrim(target_action_type))
        when 'feed' then 'feeding'
        when 'treat' then 'treatment'
        when 'harvest' then 'harvest'
        else null
    end;

    if expected_entity_type is null then
        raise exception 'Unsupported apiary action type.'
            using errcode = '22023';
    end if;

    if pg_catalog.jsonb_typeof(target_documents) is distinct from 'array' then
        raise exception 'The apiary action documents must be a JSON array.'
            using errcode = '22023';
    end if;

    document_count := pg_catalog.jsonb_array_length(target_documents);
    if document_count < 2 or document_count > 2000 or document_count % 2 <> 0 then
        raise exception 'An apiary action must contain one record and one activity event per hive, for up to 1,000 hives.'
            using errcode = '22023';
    end if;

    if target_workspace_id is null then
        -- The authenticated account's effective quota is server-derived (including active
        -- Commercial overrides); client-side locked-hive state is only a UI convenience.
        select entitlement.effective_hive_limit
          into effective_hive_limit
          from public.beekeep_my_hive_entitlement() entitlement;

        if not found or effective_hive_limit is null then
            raise exception 'The account hive limit could not be verified. Sync and try again.'
                using errcode = '42501';
        end if;

        -- Personal cloud rows are always scoped to the authenticated account.
        if not exists (
            select 1 from public.beekeep_documents d
            where d.user_id = caller_id
              and d.entity_type = 'apiary'
              and d.entity_id = target_apiary_id
              and not d.deleted
        ) then
            raise exception 'This apiary is not available in the selected personal workspace.'
                using errcode = '42501';
        end if;

        select pg_catalog.btrim(d.payload::jsonb ->> 'name')
          into apiary_name
          from public.beekeep_documents d
         where d.user_id = caller_id
           and d.entity_type = 'apiary'
           and d.entity_id = target_apiary_id
           and not d.deleted
         limit 1;
    else
        -- A team write requires current membership, a writable role, and the owner's
        -- active Commercial entitlement. RLS independently checks the same scope at insert.
        if not public.has_beekeep_workspace_role(
            target_workspace_id,
            array['owner', 'admin', 'member']::text[]
        ) then
            raise exception 'You do not have permission to add records to this team workspace.'
                using errcode = '42501';
        end if;

        if not coalesce((
            select entitlement.active
            from public.beekeep_workspace_owner_commercial_entitlement(target_workspace_id) entitlement
        ), false) then
            raise exception 'The team workspace owner must have an active BeeKeep Commercial subscription to sync apiary actions.'
                using errcode = '42501';
        end if;

        -- Use the owner's server-resolved quota, never the invited member's personal cap.
        select entitlement.effective_hive_limit
          into effective_hive_limit
          from public.beekeep_workspace_owner_quota_entitlement(target_workspace_id) entitlement;

        if not found or effective_hive_limit is null then
            raise exception 'The workspace hive limit could not be verified. Sync and try again.'
                using errcode = '42501';
        end if;

        if not exists (
            select 1 from public.beekeep_workspaces w
            where w.id = target_workspace_id
              and not w.is_personal
        ) then
            raise exception 'The selected team workspace could not be verified.'
                using errcode = '42501';
        end if;

        if not exists (
            select 1 from public.beekeep_workspace_documents d
            where d.workspace_id = target_workspace_id
              and d.entity_type = 'apiary'
              and d.entity_id = target_apiary_id
              and not d.deleted
        ) then
            raise exception 'This apiary is not available in the selected team workspace.'
                using errcode = '42501';
        end if;

        select pg_catalog.btrim(d.payload::jsonb ->> 'name')
          into apiary_name
          from public.beekeep_workspace_documents d
         where d.workspace_id = target_workspace_id
           and d.entity_type = 'apiary'
           and d.entity_id = target_apiary_id
           and not d.deleted
         limit 1;
    end if;

    if pg_catalog.lower(pg_catalog.btrim(apiary_name)) <>
       pg_catalog.lower(pg_catalog.btrim(target_apiary_name)) then
        raise exception 'The apiary name changed. Refresh the apiary and try again.'
            using errcode = '40001';
    end if;

    -- The envelope can contain only action documents and matching activity events.
    if exists (
        select 1
        from pg_catalog.jsonb_to_recordset(target_documents) as x(
            entity_type text,
            entity_id bigint,
            payload text,
            updated_at bigint,
            deleted boolean,
            device_id text
        )
        where x.entity_type not in (expected_entity_type, 'event')
           or x.entity_id is null or x.entity_id <= 0
           or x.payload is null or x.updated_at is null or x.updated_at <= 0
           or coalesce(x.deleted, false)
           or nullif(pg_catalog.btrim(x.device_id), '') is null
    ) then
        raise exception 'The apiary action contains an invalid or unsupported document.'
            using errcode = '22023';
    end if;

    if exists (
        select 1
        from pg_catalog.jsonb_to_recordset(target_documents) as x(
            entity_type text,
            entity_id bigint,
            payload text,
            updated_at bigint,
            deleted boolean,
            device_id text
        )
        where x.entity_id is distinct from
              nullif((x.payload::jsonb ->> 'id'), '')::bigint
           or (x.payload::jsonb ->> 'hive_id') is null
           or (x.payload::jsonb ->> 'batch_id') is distinct from target_batch_id::text
           or (x.payload::jsonb ->> 'apiary_id') is distinct from target_apiary_id::text
           or (x.payload::jsonb ->> 'updated_at')::bigint is distinct from x.updated_at
    ) then
        raise exception 'The apiary action document identity or batch metadata is invalid.'
            using errcode = '22023';
    end if;

    select
        pg_catalog.count(*) filter (where x.entity_type = expected_entity_type),
        pg_catalog.count(*) filter (where x.entity_type = 'event'),
        pg_catalog.array_agg(distinct (x.payload::jsonb ->> 'hive_id')::bigint)
            filter (where x.entity_type = expected_entity_type)
      into action_count, event_count, target_hive_ids
      from pg_catalog.jsonb_to_recordset(target_documents) as x(
          entity_type text,
          entity_id bigint,
          payload text,
          updated_at bigint,
          deleted boolean,
          device_id text
      );

    if action_count < 1 or action_count <> event_count
       or action_count * 2 <> document_count
       or pg_catalog.cardinality(target_hive_ids) <> action_count then
        raise exception 'Each selected hive must have exactly one unique action record and one matching event.'
            using errcode = '22023';
    end if;

    -- Validate the action's own values before accepting it into shared history.
    if expected_entity_type = 'feeding' and exists (
        select 1
        from pg_catalog.jsonb_to_recordset(target_documents) as x(
            entity_type text, entity_id bigint, payload text
        )
        where x.entity_type = expected_entity_type
          and (
              nullif(pg_catalog.btrim(x.payload::jsonb ->> 'feed_type'), '') is null
              or nullif(pg_catalog.btrim(x.payload::jsonb ->> 'unit'), '') is null
              or coalesce((x.payload::jsonb ->> 'amount')::numeric, 0) <= 0
          )
    ) then
        raise exception 'Every feeding record needs a type, a positive amount, and a unit.'
            using errcode = '22023';
    elsif expected_entity_type = 'treatment' and exists (
        select 1
        from pg_catalog.jsonb_to_recordset(target_documents) as x(
            entity_type text, entity_id bigint, payload text
        )
        where x.entity_type = expected_entity_type
          and (
              nullif(pg_catalog.btrim(x.payload::jsonb ->> 'treatment_type'), '') is null
              or nullif(pg_catalog.btrim(x.payload::jsonb ->> 'product'), '') is null
          )
    ) then
        raise exception 'Every treatment record needs a type and product.'
            using errcode = '22023';
    elsif expected_entity_type = 'harvest' and exists (
        select 1
        from pg_catalog.jsonb_to_recordset(target_documents) as x(
            entity_type text, entity_id bigint, payload text
        )
        where x.entity_type = expected_entity_type
          and (
              nullif(pg_catalog.btrim(x.payload::jsonb ->> 'weight_unit'), '') is null
              or coalesce((x.payload::jsonb ->> 'supers_pulled')::integer, -1) < 0
              or coalesce((x.payload::jsonb ->> 'wet_honey_weight')::numeric, -1) < 0
              or coalesce((x.payload::jsonb ->> 'dry_honey_weight')::numeric, -1) < 0
              or coalesce((x.payload::jsonb ->> 'wax_weight')::numeric, -1) < 0
              or coalesce((x.payload::jsonb ->> 'propolis_weight')::numeric, -1) < 0
          )
    ) then
        raise exception 'Harvest values must be non-negative and include a weight unit.'
            using errcode = '22023';
    end if;

    -- Every event must match exactly one action row by both document ID and hive ID.
    if exists (
        with docs as (
            select x.entity_type, x.entity_id, x.payload::jsonb as body
            from pg_catalog.jsonb_to_recordset(target_documents) as x(
                entity_type text, entity_id bigint, payload text
            )
        ),
        action_rows as (
            select entity_id, (body ->> 'hive_id')::bigint as hive_id
            from docs where entity_type = expected_entity_type
        ),
        event_rows as (
            select entity_id, (body ->> 'hive_id')::bigint as hive_id,
                   body ->> 'type' as event_type
            from docs where entity_type = 'event'
        )
        select 1
        from action_rows a
        full join event_rows e
          on e.entity_id = a.entity_id and e.hive_id = a.hive_id
        where a.entity_id is null
           or e.entity_id is null
           or e.event_type is distinct from expected_entity_type
    ) then
        raise exception 'The activity events do not match the selected action records.'
            using errcode = '22023';
    end if;

    -- Check that every selected hive is still active and still belongs to this apiary
    -- in the same cloud scope being written.
    if target_workspace_id is null then
        select pg_catalog.count(*)
          into matched_hive_count
          from public.beekeep_documents h
         where h.user_id = caller_id
           and h.entity_type = 'hive'
           and h.entity_id = any(target_hive_ids)
           and not h.deleted
           and coalesce(h.payload::jsonb ->> 'status', 'ACTIVE') = 'ACTIVE'
           and (
               h.payload::jsonb ->> 'apiary_id' = target_apiary_id::text
               or (
                   h.payload::jsonb ->> 'apiary_id' is null
                   and pg_catalog.lower(pg_catalog.btrim(h.payload::jsonb ->> 'apiary')) =
                       pg_catalog.lower(pg_catalog.btrim(apiary_name))
               )
           );
    else
        select pg_catalog.count(*)
          into matched_hive_count
          from public.beekeep_workspace_documents h
         where h.workspace_id = target_workspace_id
           and h.entity_type = 'hive'
           and h.entity_id = any(target_hive_ids)
           and not h.deleted
           and coalesce(h.payload::jsonb ->> 'status', 'ACTIVE') = 'ACTIVE'
           and (
               h.payload::jsonb ->> 'apiary_id' = target_apiary_id::text
               or (
                   h.payload::jsonb ->> 'apiary_id' is null
                   and pg_catalog.lower(pg_catalog.btrim(h.payload::jsonb ->> 'apiary')) =
                       pg_catalog.lower(pg_catalog.btrim(apiary_name))
               )
           );
    end if;

    if matched_hive_count <> action_count then
        raise exception 'One or more selected hives are no longer active in this apiary. Refresh and try again.'
            using errcode = '40001';
    end if;

    -- Mirror the app's plan priority order across the entire personal/team dataset:
    -- active hives first, then numeric hive number, text number, apiary name and ID.
    with hive_rows as (
        select d.entity_id, d.payload
          from public.beekeep_documents d
         where target_workspace_id is null
           and d.user_id = caller_id
           and d.entity_type = 'hive'
           and not d.deleted
        union all
        select d.entity_id, d.payload
          from public.beekeep_workspace_documents d
         where target_workspace_id is not null
           and d.workspace_id = target_workspace_id
           and d.entity_type = 'hive'
           and not d.deleted
    ),
    ranked_hives as (
        select
            h.entity_id,
            pg_catalog.row_number() over (
                order by
                    case when coalesce(h.payload::jsonb ->> 'status', 'ACTIVE') = 'ACTIVE'
                         then 0 else 1 end,
                    case
                        when coalesce(h.payload::jsonb ->> 'number', '') ~ '^-?[0-9]+$'
                        then case
                            when (h.payload::jsonb ->> 'number')::numeric
                                 between -2147483648 and 2147483647
                            then (h.payload::jsonb ->> 'number')::integer
                            else 2147483647
                        end
                        else 2147483647
                    end,
                    pg_catalog.lower(coalesce(h.payload::jsonb ->> 'number', '')),
                    pg_catalog.lower(coalesce(h.payload::jsonb ->> 'apiary', '')),
                    h.entity_id
            ) as quota_rank
        from hive_rows h
    )
    select pg_catalog.count(*)
      into locked_hive_count
      from ranked_hives h
     where h.entity_id = any(target_hive_ids)
       and h.quota_rank > effective_hive_limit;

    if locked_hive_count > 0 then
        raise exception 'One or more selected hives are locked by the current account or workspace plan.'
            using errcode = '42501';
    end if;

    -- Serialize retries for this scope + batch. A fully committed retry is accepted only
    -- when every row is identical; partial or conflicting prior data is refused.
    perform pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended(
            coalesce(target_workspace_id::text, 'personal:' || caller_id::text) ||
            ':' || target_batch_id::text,
            0
        )
    );

    if target_workspace_id is null then
        select pg_catalog.count(*)
          into existing_count
          from pg_catalog.jsonb_to_recordset(target_documents) as x(
              entity_type text, entity_id bigint
          )
          join public.beekeep_documents d
            on d.user_id = caller_id
           and d.entity_type = x.entity_type
           and d.entity_id = x.entity_id;

        select pg_catalog.count(*)
          into mismatched_existing_count
          from pg_catalog.jsonb_to_recordset(target_documents) as x(
              entity_type text, entity_id bigint, payload text,
              updated_at bigint, deleted boolean, device_id text
          )
          join public.beekeep_documents d
            on d.user_id = caller_id
           and d.entity_type = x.entity_type
           and d.entity_id = x.entity_id
          where d.payload is distinct from x.payload
             or d.updated_at is distinct from x.updated_at
             or d.deleted is distinct from coalesce(x.deleted, false);
    else
        select pg_catalog.count(*)
          into existing_count
          from pg_catalog.jsonb_to_recordset(target_documents) as x(
              entity_type text, entity_id bigint
          )
          join public.beekeep_workspace_documents d
            on d.workspace_id = target_workspace_id
           and d.entity_type = x.entity_type
           and d.entity_id = x.entity_id;

        select pg_catalog.count(*)
          into mismatched_existing_count
          from pg_catalog.jsonb_to_recordset(target_documents) as x(
              entity_type text, entity_id bigint, payload text,
              updated_at bigint, deleted boolean, device_id text
          )
          join public.beekeep_workspace_documents d
            on d.workspace_id = target_workspace_id
           and d.entity_type = x.entity_type
           and d.entity_id = x.entity_id
          where d.payload is distinct from x.payload
             or d.updated_at is distinct from x.updated_at
             or d.deleted is distinct from coalesce(x.deleted, false);
    end if;

    if mismatched_existing_count > 0 or
       (existing_count > 0 and existing_count <> document_count) then
        raise exception 'This batch ID or record ID already belongs to different cloud data. No records were changed.'
            using errcode = '23505';
    end if;

    if existing_count = document_count then
        return query select action_count, true;
        return;
    end if;

    if target_workspace_id is null then
        insert into public.beekeep_documents (
            user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
        )
        select
            caller_id, x.entity_type, x.entity_id, x.payload, x.updated_at,
            coalesce(x.deleted, false), x.device_id
        from pg_catalog.jsonb_to_recordset(target_documents) as x(
            entity_type text, entity_id bigint, payload text,
            updated_at bigint, deleted boolean, device_id text
        );
    else
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at,
            deleted, device_id, updated_by
        )
        select
            target_workspace_id, x.entity_type, x.entity_id, x.payload, x.updated_at,
            coalesce(x.deleted, false), x.device_id, caller_id
        from pg_catalog.jsonb_to_recordset(target_documents) as x(
            entity_type text, entity_id bigint, payload text,
            updated_at bigint, deleted boolean, device_id text
        );
    end if;

    return query select action_count, false;
end;
$function$;

revoke all on function public.beekeep_apply_action_batch(
    uuid, bigint, text, uuid, text, jsonb
) from public, anon;
grant execute on function public.beekeep_apply_action_batch(
    uuid, bigint, text, uuid, text, jsonb
) to authenticated;

commit;
