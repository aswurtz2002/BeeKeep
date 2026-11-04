-- Automatic Workspace Actions logging and offline replay regression coverage.
-- Fixtures are transactional and leave no rows behind.

begin;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000361', 'auto-log-owner@example.test',
  '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb,
  '{"username":"ActionOwner"}'::jsonb),
 ('00000000-0000-0000-0000-000000000362', 'auto-log-member@example.test',
  '{}'::jsonb, '{"username":"ActionMember"}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

create temporary table automatic_action_log_context (
    key text primary key,
    value text not null
);
grant select, insert, update on automatic_action_log_context to authenticated;

set local role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000361', false);

insert into automatic_action_log_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Automatic Actions Test');

insert into automatic_action_log_context(key, value)
select 'member_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from automatic_action_log_context where key = 'workspace_id'),
    'auto-log-member@example.test',
    'member'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000362', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from automatic_action_log_context where key = 'member_token')
);

insert into automatic_action_log_context(key, value)
values (
    'base_ms',
    ((extract(epoch from pg_catalog.clock_timestamp()) * 1000)::bigint)::text
);

-- A member creates an apiary, hive, and inspection in one synchronized write.
-- The trigger owns action type, actor username, target, and description.
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id,
    updated_by, client_action_id, action_at
)
values
 (
  (select value::uuid from automatic_action_log_context where key = 'workspace_id'),
  'apiary', 96301,
  jsonb_build_object('id',96301,'name','North','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms'))::text,
  (select value::bigint from automatic_action_log_context where key='base_ms'),
  false,'automatic-log-test',auth.uid(),'00000000-0000-4000-8000-000000000361',
  (select value::bigint from automatic_action_log_context where key='base_ms')
 ),
 (
  (select value::uuid from automatic_action_log_context where key = 'workspace_id'),
  'hive', 96302,
  jsonb_build_object(
      'id',96302,'number','12','apiary','North','apiary_id',96301,
      'status','ACTIVE','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms')
  )::text,
  (select value::bigint from automatic_action_log_context where key='base_ms'),
  false,'automatic-log-test',auth.uid(),'00000000-0000-4000-8000-000000000362',
  (select value::bigint from automatic_action_log_context where key='base_ms')
 ),
 (
  (select value::uuid from automatic_action_log_context where key = 'workspace_id'),
  'inspection', 96303,
  jsonb_build_object(
      'id',96303,'hive_id',96302,'created_at',(select value::bigint from automatic_action_log_context where key='base_ms'),
      'notes','Initial inspection','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms')
  )::text,
  (select value::bigint from automatic_action_log_context where key='base_ms'),
  false,'automatic-log-test',auth.uid(),'00000000-0000-4000-8000-000000000363',
  (select value::bigint from automatic_action_log_context where key='base_ms')
 );

-- Standalone Feed/Treatment/Harvest saves must be logged individually too.
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id,
    updated_by, client_action_id, action_at
)
values
 (
  (select value::uuid from automatic_action_log_context where key='workspace_id'),
  'feeding', 96304,
  jsonb_build_object('id',96304,'hive_id',96302,'apiary_id',96301,'feed_type','Sugar syrup','ratio','1:1','amount',2,'unit','L','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 8)::text,
  (select value::bigint from automatic_action_log_context where key='base_ms') + 8,
  false,'automatic-log-test',auth.uid(),'00000000-0000-4000-8000-000000000370',
  (select value::bigint from automatic_action_log_context where key='base_ms') + 8
 ),
 (
  (select value::uuid from automatic_action_log_context where key='workspace_id'),
  'treatment', 96305,
  jsonb_build_object('id',96305,'hive_id',96302,'apiary_id',96301,'treatment_type','Varroa','product','Oxalic acid','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 9)::text,
  (select value::bigint from automatic_action_log_context where key='base_ms') + 9,
  false,'automatic-log-test',auth.uid(),'00000000-0000-4000-8000-000000000371',
  (select value::bigint from automatic_action_log_context where key='base_ms') + 9
 ),
 (
  (select value::uuid from automatic_action_log_context where key='workspace_id'),
  'harvest', 96306,
  jsonb_build_object('id',96306,'hive_id',96302,'apiary_id',96301,'dry_honey_weight',12,'weight_unit','kg','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 10)::text,
  (select value::bigint from automatic_action_log_context where key='base_ms') + 10,
  false,'automatic-log-test',auth.uid(),'00000000-0000-4000-8000-000000000372',
  (select value::bigint from automatic_action_log_context where key='base_ms') + 10
 );

update public.beekeep_workspace_documents
set payload = jsonb_build_object('id',96304,'hive_id',96302,'apiary_id',96301,'feed_type','Fondant','ratio','','amount',1,'unit','kg','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 11)::text,
    updated_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 11,
    client_action_id = '00000000-0000-4000-8000-000000000373',
    action_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 11,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
  and entity_type = 'feeding' and entity_id = 96304;

-- Members can write business documents, but cannot read the owner-only activity log.
do $member_cannot_read_assertion$
declare visible_count integer;
begin
    select count(*) into visible_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id');
    if visible_count <> 0 then
        raise exception 'A regular workspace member unexpectedly read the owner-only Actions log';
    end if;
end;
$member_cannot_read_assertion$;

-- Direct log writes are unavailable even to a legitimate team member.
do $member_direct_write_assertion$
declare insert_denied boolean := false; update_denied boolean := false; delete_denied boolean := false;
begin
    begin
        insert into public.beekeep_workspace_action_log (
            workspace_id, client_action_id, user_id, username,
            action_type, target_type, description, action_at
        ) values (
            (select value::uuid from automatic_action_log_context where key='workspace_id'),
            '00000000-0000-4000-8000-000000000399',
            auth.uid(), 'Forged', 'hive_added', 'hive', 'Forged action',
            pg_catalog.clock_timestamp()
        );
    exception when insufficient_privilege then insert_denied := true;
    end;

    begin
        update public.beekeep_workspace_action_log
        set description = 'Forged edit'
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id');
    exception when insufficient_privilege then update_denied := true;
    end;

    begin
        delete from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id');
    exception when insufficient_privilege then delete_denied := true;
    end;

    if not insert_denied or not update_denied or not delete_denied then
        raise exception 'A workspace member unexpectedly received direct activity-log write privileges';
    end if;
end;
$member_direct_write_assertion$;

-- Edit the three record types; the server generates their descriptions.
update public.beekeep_workspace_documents
set payload = jsonb_build_object(
        'id',96301,'name','North Yard','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 1
    )::text,
    updated_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 1,
    client_action_id = '00000000-0000-4000-8000-000000000364',
    action_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 1,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
  and entity_type = 'apiary' and entity_id = 96301;

-- Reusing a previously logged UUID on the same row must not suppress a new edit.
update public.beekeep_workspace_documents
set payload = jsonb_build_object(
        'id',96301,'name','North Yard 2',
        'updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 7
    )::text,
    updated_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 7,
    client_action_id = '00000000-0000-4000-8000-000000000361',
    action_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 7,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
  and entity_type = 'apiary' and entity_id = 96301;

update public.beekeep_workspace_documents
set payload = jsonb_build_object(
        'id',96302,'number','12A','apiary','North Yard','apiary_id',96301,
        'status','ACTIVE','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 2
    )::text,
    updated_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 2,
    client_action_id = '00000000-0000-4000-8000-000000000365',
    action_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 2,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
  and entity_type = 'hive' and entity_id = 96302;

update public.beekeep_workspace_documents
set payload = jsonb_build_object(
        'id',96303,'hive_id',96302,'created_at',(select value::bigint from automatic_action_log_context where key='base_ms'),
        'notes','Edited inspection','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 3
    )::text,
    updated_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 3,
    client_action_id = '00000000-0000-4000-8000-000000000366',
    action_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 3,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
  and entity_type = 'inspection' and entity_id = 96303;

-- Replay two offline edits to the same hive in original order. The second snapshot
-- is the cloud winner, but both accepted changes become separate log entries.
select *
from public.beekeep_replay_workspace_action_changes(
    (select value::uuid from automatic_action_log_context where key='workspace_id'),
    jsonb_build_array(
        jsonb_build_object(
            'client_action_id','00000000-0000-4000-8000-000000000367',
            'entity_type','hive','entity_id',96302,'operation','upsert',
            'payload',jsonb_build_object(
                'id',96302,'number','Offline 1','apiary','North Yard','apiary_id',96301,
                'status','ACTIVE','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 4
            )::text,
            'action_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 4,
            'device_id','offline-test'
        ),
        jsonb_build_object(
            'client_action_id','00000000-0000-4000-8000-000000000368',
            'entity_type','hive','entity_id',96302,'operation','upsert',
            'payload',jsonb_build_object(
                'id',96302,'number','Offline 2','apiary','North Yard','apiary_id',96301,
                'status','ACTIVE','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 5
            )::text,
            'action_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 5,
            'device_id','offline-test'
        )
    )
);

-- Exact retry after the response was lost: the earlier snapshot is now stale and the
-- latest snapshot is identical. Neither may create a duplicate action-log entry.
select *
from public.beekeep_replay_workspace_action_changes(
    (select value::uuid from automatic_action_log_context where key='workspace_id'),
    jsonb_build_array(
        jsonb_build_object(
            'client_action_id','00000000-0000-4000-8000-000000000367',
            'entity_type','hive','entity_id',96302,'operation','upsert',
            'payload',jsonb_build_object(
                'id',96302,'number','Offline 1','apiary','North Yard','apiary_id',96301,
                'status','ACTIVE','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 4
            )::text,
            'action_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 4,
            'device_id','offline-test'
        ),
        jsonb_build_object(
            'client_action_id','00000000-0000-4000-8000-000000000368',
            'entity_type','hive','entity_id',96302,'operation','upsert',
            'payload',jsonb_build_object(
                'id',96302,'number','Offline 2','apiary','North Yard','apiary_id',96301,
                'status','ACTIVE','updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 5
            )::text,
            'action_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 5,
            'device_id','offline-test'
        )
    )
);

-- A real hive deletion adds one log entry but leaves the underlying document as a tombstone.
update public.beekeep_workspace_documents
set payload = jsonb_build_object(
        'id',96302,'updated_at',(select value::bigint from automatic_action_log_context where key='base_ms') + 6
    )::text,
    updated_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 6,
    deleted = true,
    client_action_id = '00000000-0000-4000-8000-000000000369',
    action_at = (select value::bigint from automatic_action_log_context where key='base_ms') + 6,
    updated_by = auth.uid()
where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
  and entity_type = 'hive' and entity_id = 96302;

-- The owner can read every entry, including the username snapshots and generated copy.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000361', false);
do $owner_log_assertion$
declare
    row_count integer;
    unique_ids integer;
    actor_name text;
    description_count integer;
    hive_deleted boolean;
begin
    select count(*) into row_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id');

    select count(distinct client_action_id) into unique_ids
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id');

    if row_count <> 14 or unique_ids <> 14 then
        raise exception 'Expected 14 distinct action entries including standalone records, offline replay, retry, and UUID reuse; found % entries and % UUIDs', row_count, unique_ids;
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'hive_added'
          and description = 'Added hive Hive 12 to Apiary North'
          and username = 'ActionMember'
    ) then
        raise exception 'Hive creation entry did not contain the member username snapshot and server-generated description';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'apiary_edited'
          and description = 'Edited apiary North Yard'
    ) then
        raise exception 'Apiary edit was not logged from the changed document';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'apiary_edited'
          and description = 'Edited apiary North Yard 2'
    ) then
        raise exception 'Reusing an old action UUID suppressed a later apiary edit';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'feeding_added'
          and description = 'Added feeding for Hive 12 in Apiary North'
          and username = 'ActionMember'
    ) then
        raise exception 'Standalone feeding creation was not logged';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'feeding_edited'
          and description = 'Edited feeding for Hive 12 in Apiary North'
    ) then
        raise exception 'Standalone feeding edit was not logged';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'treatment_added'
          and description = 'Added treatment for Hive 12 in Apiary North'
    ) then
        raise exception 'Standalone treatment creation was not logged';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'harvest_added'
          and description = 'Added harvest for Hive 12 in Apiary North'
    ) then
        raise exception 'Standalone harvest creation was not logged';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'inspection_created'
          and description = 'Created inspection for Hive 12'
    ) then
        raise exception 'Inspection creation was not logged';
    end if;

    select count(*) into description_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
      and description in (
          'Edited hive Hive Offline 1 in Apiary North Yard',
          'Edited hive Hive Offline 2 in Apiary North Yard'
      );
    if description_count <> 2 then
        raise exception 'Both offline edits to the same hive were not preserved as separate descriptions';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
          and action_type = 'hive_deleted'
          and description = 'Deleted hive Hive Offline 2 from Apiary North Yard'
    ) then
        raise exception 'Hive deletion was not logged using the pre-delete record snapshot';
    end if;

    select deleted into hive_deleted
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from automatic_action_log_context where key='workspace_id')
      and entity_type = 'hive' and entity_id = 96302;
    if hive_deleted is distinct from true then
        raise exception 'The real hive tombstone was not preserved after activity logging';
    end if;
end;
$owner_log_assertion$;

reset role;
rollback;

select 'Automatic workspace Actions logging and offline replay tests passed' as result;
