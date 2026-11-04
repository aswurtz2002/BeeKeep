-- Automatic team Workspace Actions logging.
-- Every tracked workspace-document mutation is audited inside PostgreSQL.
-- Offline replay metadata is supplied per mutation, but descriptions and actor identity
-- are derived from the authenticated database write and stored record snapshots.

begin;

alter table public.beekeep_workspace_documents
    add column if not exists client_action_id uuid,
    add column if not exists action_at bigint;

comment on column public.beekeep_workspace_documents.client_action_id is
    'Idempotency UUID for one client-side workspace record mutation.';
comment on column public.beekeep_workspace_documents.action_at is
    'Original local action time in Unix milliseconds; distinct from server sync time.';

-- Enforce one action UUID per workspace document mutation even when multiple
-- records are sent in one direct PostgREST statement.
create unique index if not exists beekeep_workspace_documents_client_action_id_uidx
    on public.beekeep_workspace_documents (workspace_id, client_action_id)
    where client_action_id is not null;

-- Stamp metadata for direct current/legacy sync writes too. A client cannot suppress
-- logging by omitting an action UUID. Exact retries with no business-record difference
-- retain the original idempotency values and do not create another entry.
create or replace function public.beekeep_workspace_document_action_stamp()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    server_now_ms bigint :=
        (extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint;
begin
    if tg_op = 'UPDATE' then
        if new.payload is not distinct from old.payload
           and new.deleted is not distinct from old.deleted then
            new.client_action_id := old.client_action_id;
            new.action_at := old.action_at;
            return new;
        end if;

        if new.client_action_id is null
           or new.client_action_id is not distinct from old.client_action_id
           or exists (
                select 1
                from public.beekeep_workspace_action_log l
                where l.workspace_id = new.workspace_id
                  and l.client_action_id = new.client_action_id
           )
           or exists (
                select 1
                from public.beekeep_workspace_documents d
                where d.workspace_id = new.workspace_id
                  and d.client_action_id = new.client_action_id
                  and (
                      tg_op <> 'UPDATE'
                      or d.entity_type is distinct from new.entity_type
                      or d.entity_id is distinct from new.entity_id
                  )
           ) then
            new.client_action_id := pg_catalog.gen_random_uuid();
        end if;
    else
        if new.client_action_id is null or exists (
            select 1
            from public.beekeep_workspace_action_log l
            where l.workspace_id = new.workspace_id
              and l.client_action_id = new.client_action_id
        ) or exists (
            select 1
            from public.beekeep_workspace_documents d
            where d.workspace_id = new.workspace_id
              and d.client_action_id = new.client_action_id
        ) then
            new.client_action_id := pg_catalog.gen_random_uuid();
        end if;
    end if;

    new.action_at := coalesce(nullif(new.action_at, 0), new.updated_at, server_now_ms);
    if new.action_at > server_now_ms + 300000 then
        -- Keep the audit timestamp grounded in server time when a device clock
        -- is materially ahead; ordinary offline past timestamps remain unchanged.
        new.action_at := server_now_ms;
    end if;
    if new.action_at <= 0 then
        new.action_at := server_now_ms;
    end if;

    return new;
end;
$function$;

revoke all on function public.beekeep_workspace_document_action_stamp()
    from public, anon, authenticated;

drop trigger if exists beekeep_workspace_document_action_stamp
    on public.beekeep_workspace_documents;
create trigger beekeep_workspace_document_action_stamp
before insert or update on public.beekeep_workspace_documents
for each row
execute function public.beekeep_workspace_document_action_stamp();

-- Descriptions are generated from the saved document snapshot, not client-provided text.
create or replace function public.beekeep_workspace_action_log_description(
    target_workspace_id uuid,
    target_entity_type text,
    target_entity_id bigint,
    target_payload jsonb,
    target_verb text
)
returns text
language plpgsql
security invoker
set search_path = ''
as $function$
declare
    record_name text;
    apiary_name text;
    hive_id bigint;
begin
    if target_entity_type = 'apiary' then
        record_name := coalesce(
            nullif(pg_catalog.btrim(target_payload ->> 'name'), ''),
            target_entity_id::text
        );
        return pg_catalog.format('%s apiary %s', target_verb, record_name);
    elsif target_entity_type = 'hive' then
        record_name := coalesce(
            nullif(pg_catalog.btrim(target_payload ->> 'number'), ''),
            target_entity_id::text
        );
        apiary_name := nullif(pg_catalog.btrim(target_payload ->> 'apiary'), '');
        if apiary_name is null then
            select nullif(pg_catalog.btrim(d.payload::jsonb ->> 'name'), '')
              into apiary_name
              from public.beekeep_workspace_documents d
             where d.workspace_id = target_workspace_id
               and d.entity_type = 'apiary'
               and d.entity_id = nullif(target_payload ->> 'apiary_id', '')::bigint
               and not d.deleted
             limit 1;
        end if;
        apiary_name := coalesce(apiary_name, 'Unknown apiary');
        if target_verb = 'Added' then
            return pg_catalog.format('Added hive Hive %s to Apiary %s', record_name, apiary_name);
        elsif target_verb = 'Deleted' then
            return pg_catalog.format('Deleted hive Hive %s from Apiary %s', record_name, apiary_name);
        end if;
        return pg_catalog.format('Edited hive Hive %s in Apiary %s', record_name, apiary_name);
    elsif target_entity_type = 'inspection' then
        hive_id := nullif(target_payload ->> 'hive_id', '')::bigint;
        select nullif(pg_catalog.btrim(d.payload::jsonb ->> 'number'), '')
          into record_name
          from public.beekeep_workspace_documents d
         where d.workspace_id = target_workspace_id
           and d.entity_type = 'hive'
           and d.entity_id = hive_id
           and not d.deleted
         limit 1;
        record_name := coalesce(record_name, coalesce(hive_id, target_entity_id)::text);
        if target_verb = 'Created' then
            return pg_catalog.format('Created inspection for Hive %s', record_name);
        end if;
        return pg_catalog.format('Edited inspection for Hive %s', record_name);
    elsif target_entity_type in ('feeding', 'treatment', 'harvest') then
        hive_id := nullif(target_payload ->> 'hive_id', '')::bigint;
        select
            nullif(pg_catalog.btrim(h.payload::jsonb ->> 'number'), ''),
            coalesce(
                nullif(pg_catalog.btrim(h.payload::jsonb ->> 'apiary'), ''),
                nullif(pg_catalog.btrim(a.payload::jsonb ->> 'name'), '')
            )
          into record_name, apiary_name
          from public.beekeep_workspace_documents h
          left join public.beekeep_workspace_documents a
            on a.workspace_id = h.workspace_id
           and a.entity_type = 'apiary'
           and a.entity_id = nullif(h.payload::jsonb ->> 'apiary_id', '')::bigint
           and not a.deleted
         where h.workspace_id = target_workspace_id
           and h.entity_type = 'hive'
           and h.entity_id = hive_id
           and not h.deleted
         limit 1;
        record_name := coalesce(record_name, hive_id::text, target_entity_id::text);
        apiary_name := coalesce(apiary_name, 'Unknown apiary');
        return pg_catalog.format(
            '%s %s for Hive %s in Apiary %s',
            target_verb,
            target_entity_type,
            record_name,
            apiary_name
        );
    end if;

    return pg_catalog.format('%s %s %s', target_verb, target_entity_type, target_entity_id);
end;
$function$;

revoke all on function public.beekeep_workspace_action_log_description(
    uuid, text, bigint, jsonb, text
) from public, anon, authenticated;

-- One statement-level trigger captures all inserted documents together. This matters
-- for atomic Apiary-wide actions: all hive rows from one mass batch become one log row.
create or replace function public.beekeep_workspace_action_log_after_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    -- Apiary, hive, and inspection records.
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
            when n.entity_type = 'apiary' and n.deleted then 'apiary_deleted'
            when n.entity_type = 'apiary' then 'apiary_added'
            when n.entity_type = 'hive' and n.deleted then 'hive_deleted'
            when n.entity_type = 'hive' then 'hive_added'
            else 'inspection_created'
        end,
        n.entity_type,
        n.entity_id::text,
        public.beekeep_workspace_action_log_description(
            n.workspace_id,
            n.entity_type,
            n.entity_id,
            n.payload::jsonb,
            case
                when n.entity_type in ('apiary', 'hive') and n.deleted then 'Deleted'
                when n.entity_type in ('apiary', 'hive') then 'Added'
                else 'Created'
            end
        ),
        pg_catalog.to_timestamp(
            coalesce(nullif(n.action_at, 0), n.updated_at)::double precision / 1000.0
        )
    from new_rows n
    left join public.beekeep_profiles p
      on p.user_id = coalesce(n.updated_by, (select auth.uid()))
    where n.entity_type in ('apiary', 'hive', 'inspection')
      and (n.entity_type <> 'inspection' or not n.deleted)
    on conflict (workspace_id, client_action_id) do nothing;

    -- Apply-to-Apiary Feed/Treatment/Harvest inserts are written by one atomic
    -- statement. Group that statement by the batch UUID to produce exactly one row.
    with action_rows as (
        select
            n.workspace_id,
            n.entity_type,
            (n.payload::jsonb ->> 'batch_id')::uuid as batch_id,
            (n.payload::jsonb ->> 'apiary_id')::bigint as apiary_id,
            (n.payload::jsonb ->> 'hive_id')::bigint as hive_id,
            coalesce(
                nullif(n.payload::jsonb ->> 'occurred_at', '')::bigint,
                nullif(n.payload::jsonb ->> 'created_at', '')::bigint,
                n.updated_at
            ) as action_at_ms,
            coalesce(n.updated_by, (select auth.uid())) as actor_id
        from new_rows n
        where n.entity_type in ('feeding', 'treatment', 'harvest')
          and nullif(n.payload::jsonb ->> 'batch_id', '') ~*
              '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    ),
    grouped_batches as (
        select
            workspace_id,
            entity_type,
            batch_id,
            pg_catalog.max(apiary_id) as apiary_id,
            pg_catalog.count(distinct hive_id)::integer as hive_count,
            pg_catalog.max(action_at_ms) as action_at_ms,
            (pg_catalog.array_agg(actor_id))[1] as actor_id
        from action_rows
        group by workspace_id, entity_type, batch_id
    )
    insert into public.beekeep_workspace_action_log (
        workspace_id, client_action_id, user_id, username,
        action_type, target_type, target_id, description, action_at
    )
    select
        b.workspace_id,
        b.batch_id,
        b.actor_id,
        coalesce(nullif(pg_catalog.btrim(p.username), ''), 'Team member'),
        case b.entity_type
            when 'feeding' then 'mass_feed'
            when 'treatment' then 'mass_treatment'
            else 'mass_harvest'
        end,
        'batch',
        b.batch_id::text,
        pg_catalog.format(
            'Mass %s applied to Apiary %s (%s hives)',
            case b.entity_type
                when 'feeding' then 'feeding'
                when 'treatment' then 'treatment'
                else 'harvest'
            end,
            coalesce(nullif(pg_catalog.btrim(a.payload::jsonb ->> 'name'), ''), b.apiary_id::text),
            b.hive_count
        ),
        pg_catalog.to_timestamp(b.action_at_ms::double precision / 1000.0)
    from grouped_batches b
    left join public.beekeep_profiles p on p.user_id = b.actor_id
    left join public.beekeep_workspace_documents a
      on a.workspace_id = b.workspace_id
     and a.entity_type = 'apiary'
     and a.entity_id = b.apiary_id
     and not a.deleted
    where b.hive_count > 0
    on conflict (workspace_id, client_action_id) do nothing;

    -- Individual Feed/Treatment/Harvest records have no batch UUID. Keep
    -- these entries separate from the Apiary-wide batch summary above.
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
            when 'feeding' then 'feeding_added'
            when 'treatment' then 'treatment_added'
            else 'harvest_added'
        end,
        n.entity_type,
        n.entity_id::text,
        public.beekeep_workspace_action_log_description(
            n.workspace_id, n.entity_type, n.entity_id, n.payload::jsonb, 'Added'
        ),
        pg_catalog.to_timestamp(
            coalesce(nullif(n.action_at, 0), n.updated_at)::double precision / 1000.0
        )
    from new_rows n
    left join public.beekeep_profiles p
      on p.user_id = coalesce(n.updated_by, (select auth.uid()))
    where n.entity_type in ('feeding', 'treatment', 'harvest')
      and nullif(n.payload::jsonb ->> 'batch_id', '') is null
    on conflict (workspace_id, client_action_id) do nothing;

    return null;
end;
$function$;

revoke all on function public.beekeep_workspace_action_log_after_insert()
    from public, anon, authenticated;

drop trigger if exists beekeep_workspace_action_log_after_insert
    on public.beekeep_workspace_documents;
create trigger beekeep_workspace_action_log_after_insert
after insert on public.beekeep_workspace_documents
referencing new table as new_rows
for each statement
execute function public.beekeep_workspace_action_log_after_insert();

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
        public.beekeep_workspace_action_log_description(
            n.workspace_id,
            n.entity_type,
            n.entity_id,
            case when n.deleted and not o.deleted then o.payload::jsonb else n.payload::jsonb end,
            case
                when n.entity_type in ('apiary', 'hive') and n.deleted and not o.deleted then 'Deleted'
                when n.entity_type in ('apiary', 'hive') then 'Edited'
                else 'Edited'
            end
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

revoke all on function public.beekeep_workspace_action_log_after_update()
    from public, anon, authenticated;

drop trigger if exists beekeep_workspace_action_log_after_update
    on public.beekeep_workspace_documents;
create trigger beekeep_workspace_action_log_after_update
after update on public.beekeep_workspace_documents
referencing old table as old_rows new table as new_rows
for each statement
execute function public.beekeep_workspace_action_log_after_update();

-- Replay a bounded page of offline record mutations in the order stored by Room.
-- This RPC does not accept action descriptions or action types: row triggers derive
-- both from the actual accepted document change. RLS still authorizes each write.
create or replace function public.beekeep_replay_workspace_action_changes(
    target_workspace_id uuid,
    target_actions jsonb
)
returns table (
    processed_actions integer,
    applied_actions integer
)
language plpgsql
security invoker
set search_path = ''
as $function$
declare
    caller_id uuid := (select auth.uid());
    item jsonb;
    v_action_id uuid;
    v_entity_type text;
    v_entity_id bigint;
    v_operation text;
    v_payload_text text;
    v_payload_json jsonb;
    v_updated_at_ms bigint;
    v_action_at_ms bigint;
    v_is_deleted boolean;
    v_device_id_value text;
    v_processed_count integer := 0;
    v_applied_count integer := 0;
    v_affected_count integer;
begin
    if caller_id is null then
        raise exception 'Sign in before syncing workspace actions.'
            using errcode = '42501';
    end if;

    if target_workspace_id is null
       or pg_catalog.jsonb_typeof(target_actions) is distinct from 'array'
       or pg_catalog.jsonb_array_length(target_actions) < 1
       or pg_catalog.jsonb_array_length(target_actions) > 50 then
        raise exception 'Workspace action replay must contain between 1 and 50 actions.'
            using errcode = '22023';
    end if;

    if not exists (
        select 1
        from public.beekeep_workspaces w
        where w.id = target_workspace_id
          and not w.is_personal
    ) or not public.has_beekeep_workspace_role(
        target_workspace_id,
        array['owner', 'admin', 'member']::text[]
    ) then
        raise exception 'You do not have permission to sync records to this team workspace.'
            using errcode = '42501';
    end if;

    if not coalesce((
        select entitlement.active
        from public.beekeep_workspace_owner_commercial_entitlement(target_workspace_id) entitlement
    ), false) then
        raise exception 'The team workspace owner must have an active BeeKeep Commercial subscription to sync records.'
            using errcode = '42501';
    end if;

    for item in
        select value
        from pg_catalog.jsonb_array_elements(target_actions) with ordinality as a(value, item_order)
        order by item_order
    loop
        v_action_id := nullif(item ->> 'client_action_id', '')::uuid;
        v_entity_type := nullif(pg_catalog.btrim(item ->> 'entity_type'), '');
        v_entity_id := nullif(item ->> 'entity_id', '')::bigint;
        v_operation := pg_catalog.lower(pg_catalog.btrim(coalesce(item ->> 'operation', '')));
        v_payload_text := item ->> 'payload';
        v_payload_json := v_payload_text::jsonb;
        v_updated_at_ms := nullif(v_payload_json ->> 'updated_at', '')::bigint;
        v_action_at_ms := coalesce(nullif(item ->> 'action_at', '')::bigint, v_updated_at_ms);
        v_is_deleted := v_operation = 'delete';
        v_device_id_value := coalesce(nullif(pg_catalog.btrim(item ->> 'device_id'), ''), 'android');

        if v_action_id is null
           or v_entity_type is null
           or v_entity_type not in ('apiary', 'hive', 'inspection')
           or v_entity_id is null or v_entity_id <= 0
           or v_operation not in ('upsert', 'delete')
           or v_payload_text is null
           or v_updated_at_ms is null or v_updated_at_ms <= 0
           or v_action_at_ms is null or v_action_at_ms <= 0
           or nullif(v_payload_json ->> 'id', '')::bigint is distinct from v_entity_id then
            raise exception 'The offline workspace action queue contains an invalid record.'
                using errcode = '22023';
        end if;

        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload,
            updated_at, deleted, device_id, updated_by, client_action_id, action_at
        )
        values (
            target_workspace_id, v_entity_type, v_entity_id, v_payload_text,
            v_updated_at_ms, v_is_deleted, v_device_id_value, caller_id, v_action_id, v_action_at_ms
        )
        on conflict (workspace_id, entity_type, entity_id)
        do update set
            payload = excluded.payload,
            updated_at = excluded.updated_at,
            deleted = excluded.deleted,
            device_id = excluded.device_id,
            updated_by = caller_id,
            client_action_id = excluded.client_action_id,
            action_at = excluded.action_at;

        get diagnostics v_affected_count = row_count;
        v_processed_count := v_processed_count + 1;
        if v_affected_count > 0 then
            v_applied_count := v_applied_count + 1;
        end if;
    end loop;

    return query select v_processed_count, v_applied_count;
end;
$function$;

revoke all on function public.beekeep_replay_workspace_action_changes(uuid, jsonb)
    from public, anon;
grant execute on function public.beekeep_replay_workspace_action_changes(uuid, jsonb)
    to authenticated;

commit;
