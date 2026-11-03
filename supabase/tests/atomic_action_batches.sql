-- Step 2D: atomic apiary-wide action batch and scope/role validation.
-- All fixtures are inside one transaction and are rolled back.

begin;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000201', 'action-batch-owner@example.test',
  '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
 ('00000000-0000-0000-0000-000000000202', 'action-batch-member@example.test', '{}'::jsonb, '{}'::jsonb),
 ('00000000-0000-0000-0000-000000000203', 'action-batch-viewer@example.test', '{}'::jsonb, '{}'::jsonb),
 ('00000000-0000-0000-0000-000000000204', 'action-batch-outsider@example.test', '{}'::jsonb, '{}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

create temporary table action_batch_context (
    key text primary key,
    value text not null
);
grant select, insert, update on action_batch_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000201', false);

insert into action_batch_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Atomic action batch test');

insert into action_batch_context(key, value)
select 'member_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from action_batch_context where key = 'workspace_id'),
    'action-batch-member@example.test',
    'member'
);

insert into action_batch_context(key, value)
select 'viewer_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from action_batch_context where key = 'workspace_id'),
    'action-batch-viewer@example.test',
    'viewer'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000202', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from action_batch_context where key = 'member_token')
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000203', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from action_batch_context where key = 'viewer_token')
);

-- Seed one apiary and two active hive profiles in the workspace.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000201', false);
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values
 ((select value::uuid from action_batch_context where key = 'workspace_id'),
  'apiary', 92000, '{"id":92000,"name":"Batch Apiary"}', 1000, false, 'test-device', auth.uid()),
 ((select value::uuid from action_batch_context where key = 'workspace_id'),
  'hive', 92001, '{"id":92001,"number":"1","apiary":"Batch Apiary","apiary_id":92000,"status":"ACTIVE"}',
  1000, false, 'test-device', auth.uid()),
 ((select value::uuid from action_batch_context where key = 'workspace_id'),
  'hive', 92002, '{"id":92002,"number":"2","apiary":"Batch Apiary","apiary_id":92000,"status":"ACTIVE"}',
  1000, false, 'test-device', auth.uid());

-- A regular team member can upload a complete batch for the team's selected apiary.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000202', false);
insert into action_batch_context(key, value)
values ('batch_id', '00000000-0000-4000-8000-000000000201')
on conflict (key) do update set value = excluded.value;

select * from public.beekeep_apply_action_batch(
    (select value::uuid from action_batch_context where key = 'workspace_id'),
    92000,
    'Batch Apiary',
    (select value::uuid from action_batch_context where key = 'batch_id'),
    'feed',
    jsonb_build_array(
        jsonb_build_object(
            'entity_type','feeding','entity_id',92011,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object(
                'id',92011,'hive_id',92001,'created_at',2000,'updated_at',2000,
                'record_uuid','batch-feed-1','apiary_id',92000,
                'batch_id',(select value from action_batch_context where key='batch_id'),
                'occurred_at',2000,'created_by',auth.uid(),'device_id','batch-device',
                'feed_type','Sugar syrup','ratio','1:1','amount',2.0,'unit','L','notes',''
            )::text
        ),
        jsonb_build_object(
            'entity_type','event','entity_id',92011,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object(
                'id',92011,'hive_id',92001,'created_at',2000,'updated_at',2000,
                'type','feeding','title','Feeding','detail','2 L • Sugar syrup 1:1',
                'apiary_id',92000,'batch_id',(select value from action_batch_context where key='batch_id')
            )::text
        ),
        jsonb_build_object(
            'entity_type','feeding','entity_id',92012,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object(
                'id',92012,'hive_id',92002,'created_at',2000,'updated_at',2000,
                'record_uuid','batch-feed-2','apiary_id',92000,
                'batch_id',(select value from action_batch_context where key='batch_id'),
                'occurred_at',2000,'created_by',auth.uid(),'device_id','batch-device',
                'feed_type','Sugar syrup','ratio','1:1','amount',2.0,'unit','L','notes',''
            )::text
        ),
        jsonb_build_object(
            'entity_type','event','entity_id',92012,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object(
                'id',92012,'hive_id',92002,'created_at',2000,'updated_at',2000,
                'type','feeding','title','Feeding','detail','2 L • Sugar syrup 1:1',
                'apiary_id',92000,'batch_id',(select value from action_batch_context where key='batch_id')
            )::text
        )
    )
);

do $success_assertion$
declare row_count integer; batch_count integer;
begin
    select count(*) into row_count
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
      and entity_type in ('feeding','event') and entity_id in (92011,92012);

    if row_count <> 4 then
        raise exception 'Expected all 4 batch documents to commit together, received %', row_count;
    end if;

    select count(*) into batch_count
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
      and entity_type = 'feeding'
      and payload::jsonb ->> 'batch_id' = (select value from action_batch_context where key='batch_id');

    if batch_count <> 2 then
        raise exception 'Expected exactly two feeding records for the apiary batch';
    end if;
end;
$success_assertion$;

-- A retry with exactly the same records is idempotent; it must not duplicate or mutate history.
select * from public.beekeep_apply_action_batch(
    (select value::uuid from action_batch_context where key = 'workspace_id'),
    92000, 'Batch Apiary',
    (select value::uuid from action_batch_context where key = 'batch_id'),
    'feed',
    jsonb_build_array(
        jsonb_build_object('entity_type','feeding','entity_id',92011,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object('id',92011,'hive_id',92001,'created_at',2000,'updated_at',2000,'record_uuid','batch-feed-1','apiary_id',92000,'batch_id',(select value from action_batch_context where key='batch_id'),'occurred_at',2000,'created_by',auth.uid(),'device_id','batch-device','feed_type','Sugar syrup','ratio','1:1','amount',2.0,'unit','L','notes','')::text),
        jsonb_build_object('entity_type','event','entity_id',92011,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object('id',92011,'hive_id',92001,'created_at',2000,'updated_at',2000,'type','feeding','title','Feeding','detail','2 L • Sugar syrup 1:1','apiary_id',92000,'batch_id',(select value from action_batch_context where key='batch_id'))::text),
        jsonb_build_object('entity_type','feeding','entity_id',92012,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object('id',92012,'hive_id',92002,'created_at',2000,'updated_at',2000,'record_uuid','batch-feed-2','apiary_id',92000,'batch_id',(select value from action_batch_context where key='batch_id'),'occurred_at',2000,'created_by',auth.uid(),'device_id','batch-device','feed_type','Sugar syrup','ratio','1:1','amount',2.0,'unit','L','notes','')::text),
        jsonb_build_object('entity_type','event','entity_id',92012,'updated_at',2000,'deleted',false,'device_id','batch-device',
            'payload',jsonb_build_object('id',92012,'hive_id',92002,'created_at',2000,'updated_at',2000,'type','feeding','title','Feeding','detail','2 L • Sugar syrup 1:1','apiary_id',92000,'batch_id',(select value from action_batch_context where key='batch_id'))::text)
    )
);

-- A target hive that is no longer active/in the apiary rejects the whole request.
do $atomic_failure_assertion$
declare denied boolean := false; inserted_count integer;
begin
    begin
        perform * from public.beekeep_apply_action_batch(
            (select value::uuid from action_batch_context where key='workspace_id'),
            92000,'Batch Apiary','00000000-0000-4000-8000-000000000202'::uuid,'feed',
            jsonb_build_array(
                jsonb_build_object('entity_type','feeding','entity_id',92021,'updated_at',3000,'deleted',false,'device_id','batch-device',
                    'payload',jsonb_build_object('id',92021,'hive_id',92001,'created_at',3000,'updated_at',3000,'record_uuid','batch-feed-3','apiary_id',92000,'batch_id','00000000-0000-4000-8000-000000000202','occurred_at',3000,'created_by',auth.uid(),'device_id','batch-device','feed_type','Sugar syrup','ratio','1:1','amount',2.0,'unit','L','notes','')::text),
                jsonb_build_object('entity_type','event','entity_id',92021,'updated_at',3000,'deleted',false,'device_id','batch-device',
                    'payload',jsonb_build_object('id',92021,'hive_id',92001,'created_at',3000,'updated_at',3000,'type','feeding','title','Feeding','detail','2 L • Sugar syrup 1:1','apiary_id',92000,'batch_id','00000000-0000-4000-8000-000000000202')::text),
                jsonb_build_object('entity_type','feeding','entity_id',92022,'updated_at',3000,'deleted',false,'device_id','batch-device',
                    'payload',jsonb_build_object('id',92022,'hive_id',92999,'created_at',3000,'updated_at',3000,'record_uuid','batch-feed-4','apiary_id',92000,'batch_id','00000000-0000-4000-8000-000000000202','occurred_at',3000,'created_by',auth.uid(),'device_id','batch-device','feed_type','Sugar syrup','ratio','1:1','amount',2.0,'unit','L','notes','')::text),
                jsonb_build_object('entity_type','event','entity_id',92022,'updated_at',3000,'deleted',false,'device_id','batch-device',
                    'payload',jsonb_build_object('id',92022,'hive_id',92999,'created_at',3000,'updated_at',3000,'type','feeding','title','Feeding','detail','2 L • Sugar syrup 1:1','apiary_id',92000,'batch_id','00000000-0000-4000-8000-000000000202')::text)
            )
        );
    exception when others then
        denied := true;
    end;

    select count(*) into inserted_count
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
      and entity_type in ('feeding','event') and entity_id in (92021,92022);

    if not denied or inserted_count <> 0 then
        raise exception 'A failed apiary action was partially committed';
    end if;
end;
$atomic_failure_assertion$;

-- Viewers and non-members cannot submit batches.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000203', false);
do $viewer_assertion$
declare denied boolean := false;
begin
    begin
        perform * from public.beekeep_apply_action_batch(
            (select value::uuid from action_batch_context where key='workspace_id'),
            92000,'Batch Apiary','00000000-0000-4000-8000-000000000203'::uuid,'feed','[{},{}]'::jsonb
        );
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Viewer unexpectedly submitted an apiary action'; end if;
end;
$viewer_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000204', false);
do $outsider_assertion$
declare denied boolean := false;
begin
    begin
        perform * from public.beekeep_apply_action_batch(
            (select value::uuid from action_batch_context where key='workspace_id'),
            92000,'Batch Apiary','00000000-0000-4000-8000-000000000204'::uuid,'feed','[{},{}]'::jsonb
        );
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Non-member unexpectedly submitted an apiary action'; end if;
end;
$outsider_assertion$;

reset role;
rollback;

select 'Atomic apiary action batch security tests passed' as result;
