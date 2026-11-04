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

-- The Actions list is intentionally Owner-only. Switch to the owner for log assertions.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000201', false);

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

    -- The workspace-document insert trigger groups all hive rows sharing one
    -- batch UUID into exactly one owner-visible Actions entry.
    if to_regclass('public.beekeep_workspace_action_log') is not null then
        select count(*) into batch_count
        from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
          and client_action_id = (select value::uuid from action_batch_context where key='batch_id')
          and action_type = 'mass_feed';

        if batch_count <> 1 then
            raise exception 'Expected one mass_feed Actions row for batch %, found %. Existing rows: %',
                (select value from action_batch_context where key='batch_id'),
                batch_count,
                coalesce((
                    select pg_catalog.string_agg(
                        l.action_type || ':' || l.description || ':' || l.client_action_id::text,
                        ' | '
                    )
                    from public.beekeep_workspace_action_log l
                    where l.workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
                ), 'none');
        end if;

        if not exists (
            select 1
            from public.beekeep_workspace_action_log
            where workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
              and client_action_id = (select value::uuid from action_batch_context where key='batch_id')
              and description = 'Mass feeding applied to Apiary Batch Apiary (2 hives)'
        ) then
            raise exception 'Mass batch entry found but its action type, batch UUID or description was incorrect';
        end if;
    end if;
end;
$success_assertion$;

-- Return to the writable member for the idempotent batch retry.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000202', false);

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

-- Verify retry idempotency through the Owner-only Actions view.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000201', false);

do $batch_retry_log_assertion$
declare logged_count integer;
begin
    if to_regclass('public.beekeep_workspace_action_log') is not null then
        select count(*) into logged_count
        from public.beekeep_workspace_action_log
        where workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
          and client_action_id = (select value::uuid from action_batch_context where key='batch_id')
          and action_type = 'mass_feed';
        if logged_count <> 1 then
            raise exception 'An idempotent batch retry duplicated its Actions entry';
        end if;
    end if;
end;
$batch_retry_log_assertion$;

-- Switch back to the member for the remaining write-permission checks.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000202', false);

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

-- Personal workspaces use the same atomic batch RPC but remain scoped to the signed-in user.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000204', false);
insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
values
 (auth.uid(),'apiary',93000,'{"id":93000,"name":"Personal Apiary"}',1000,false,'personal-test-device'),
 (auth.uid(),'hive',93001,'{"id":93001,"number":"P1","apiary":"Personal Apiary","apiary_id":93000,"status":"ACTIVE"}',1000,false,'personal-test-device');

select * from public.beekeep_apply_action_batch(
    null::uuid,
    93000,
    'Personal Apiary',
    '00000000-0000-4000-8000-000000000205'::uuid,
    'feed',
    jsonb_build_array(
        jsonb_build_object(
            'entity_type','feeding','entity_id',93011,'updated_at',4000,'deleted',false,'device_id','personal-test-device',
            'payload',jsonb_build_object(
                'id',93011,'hive_id',93001,'created_at',4000,'updated_at',4000,
                'record_uuid','personal-batch-feed','apiary_id',93000,
                'batch_id','00000000-0000-4000-8000-000000000205',
                'occurred_at',4000,'created_by',auth.uid(),'device_id','personal-test-device',
                'feed_type','Sugar syrup','ratio','1:1','amount',1.0,'unit','L','notes',''
            )::text
        ),
        jsonb_build_object(
            'entity_type','event','entity_id',93011,'updated_at',4000,'deleted',false,'device_id','personal-test-device',
            'payload',jsonb_build_object(
                'id',93011,'hive_id',93001,'created_at',4000,'updated_at',4000,
                'type','feeding','title','Feeding','detail','1 L • Sugar syrup 1:1',
                'apiary_id',93000,'batch_id','00000000-0000-4000-8000-000000000205'
            )::text
        )
    )
);

do $personal_assertion$
declare saved_count integer;
begin
    select count(*) into saved_count
    from public.beekeep_documents
    where user_id = auth.uid()
      and entity_type in ('feeding','event')
      and entity_id = 93011;
    if saved_count <> 2 then
        raise exception 'Personal apiary batch did not save both the action and event documents';
    end if;
end;
$personal_assertion$;

-- Seed 16 existing hives while the test account has Commercial, then expire it.
-- Existing records survive downgrade; only writes to plan-locked hives are denied.
reset role;
update auth.users
set raw_app_meta_data = jsonb_set(
    jsonb_set(coalesce(raw_app_meta_data, '{}'::jsonb),
        '{beekeep_plan}', '"commercial_monthly"'::jsonb, true),
    '{beekeep_subscription_expires_at}', '"2099-01-01T00:00:00Z"'::jsonb, true
)
where id = '00000000-0000-0000-0000-000000000204';

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000204', false);

insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
select
    auth.uid(),
    'hive',
    94000 + gs,
    jsonb_build_object(
        'id', 94000 + gs,
        'number', gs::text,
        'apiary', 'Personal Apiary',
        'apiary_id', 93000,
        'status', 'ACTIVE'
    )::text,
    4100,
    false,
    'personal-test-device'
from generate_series(1, 15) gs;

-- Downgrade the seeded account to Hobbyist while leaving all 16 records intact.
reset role;
update auth.users
set raw_app_meta_data = jsonb_set(
    coalesce(raw_app_meta_data, '{}'::jsonb),
    '{beekeep_subscription_expires_at}',
    '"2020-01-01T00:00:00Z"'::jsonb,
    true
)
where id = '00000000-0000-0000-0000-000000000204';

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000204', false);

do $locked_hive_assertion$
declare denied boolean := false; saved_count integer;
begin
    begin
        perform * from public.beekeep_apply_action_batch(
            null::uuid,
            93000,
            'Personal Apiary',
            '00000000-0000-4000-8000-000000000207'::uuid,
            'feed',
            jsonb_build_array(
                jsonb_build_object(
                    'entity_type','feeding','entity_id',93021,'updated_at',5000,'deleted',false,'device_id','personal-test-device',
                    'payload',jsonb_build_object(
                        'id',93021,'hive_id',93001,'created_at',5000,'updated_at',5000,
                        'record_uuid','locked-personal-feed','apiary_id',93000,
                        'batch_id','00000000-0000-4000-8000-000000000207',
                        'occurred_at',5000,'created_by',auth.uid(),'device_id','personal-test-device',
                        'feed_type','Sugar syrup','ratio','1:1','amount',1.0,'unit','L','notes',''
                    )::text
                ),
                jsonb_build_object(
                    'entity_type','event','entity_id',93021,'updated_at',5000,'deleted',false,'device_id','personal-test-device',
                    'payload',jsonb_build_object(
                        'id',93021,'hive_id',93001,'created_at',5000,'updated_at',5000,
                        'type','feeding','title','Feeding','detail','1 L • Sugar syrup 1:1',
                        'apiary_id',93000,'batch_id','00000000-0000-4000-8000-000000000207'
                    )::text
                )
            )
        );
    exception when insufficient_privilege then
        denied := true;
    end;

    select count(*) into saved_count
    from public.beekeep_documents
    where user_id = auth.uid()
      and entity_type in ('feeding','event')
      and entity_id = 93021;

    if not denied or saved_count <> 0 then
        raise exception 'A plan-locked personal hive accepted a batch action';
    end if;
end;
$locked_hive_assertion$;

-- Expiring the workspace owner's Commercial entitlement blocks even a regular member's
-- otherwise-valid team action. Existing shared data remains readable and unchanged.
reset role;
update auth.users
set raw_app_meta_data = jsonb_set(
    coalesce(raw_app_meta_data, '{}'::jsonb),
    '{beekeep_subscription_expires_at}',
    '"2020-01-01T00:00:00Z"'::jsonb,
    true
)
where id = '00000000-0000-0000-0000-000000000201';

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000202', false);
do $downgrade_assertion$
declare denied boolean := false; saved_count integer;
begin
    begin
        perform * from public.beekeep_apply_action_batch(
            (select value::uuid from action_batch_context where key='workspace_id'),
            92000,'Batch Apiary','00000000-0000-4000-8000-000000000206'::uuid,'feed','[{},{}]'::jsonb
        );
    exception when insufficient_privilege then
        denied := true;
    end;

    select count(*) into saved_count
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from action_batch_context where key='workspace_id')
      and entity_type in ('feeding','event') and entity_id in (92031,92032);
    if not denied or saved_count <> 0 then
        raise exception 'A workspace action was not blocked after the owner Commercial entitlement expired';
    end if;
end;
$downgrade_assertion$;

reset role;
rollback;

select 'Atomic apiary action batch security tests passed' as result;
