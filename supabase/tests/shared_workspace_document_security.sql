-- Stage 3A shared-record access control assertions.
-- Requires the Stage 1/2 schema, invitation functions, and Stage 3A migration.

-- Non-member test account, created as the database owner before switching to authenticated.
insert into auth.users (id, email, raw_user_meta_data)
values ('00000000-0000-0000-0000-000000000003', 'non-member@example.test', '{}'::jsonb)
on conflict (id) do nothing;

create temporary table stage3_workspace_document_context (
    key text primary key,
    value text
);
grant select, insert, update on stage3_workspace_document_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

insert into stage3_workspace_document_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Shared Record Stage 3 Test');

insert into stage3_workspace_document_context(key, value)
select 'invite_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from stage3_workspace_document_context where key = 'workspace_id'),
    'owner-two@example.test',
    'member'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from stage3_workspace_document_context where key = 'invite_token')
);

-- A valid workspace member can see and create shared records.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    (select value::uuid from stage3_workspace_document_context where key = 'workspace_id'),
    'hive', 7001, '{"id":7001,"number":"Stage 3 shared hive"}', 1000, false, 'sql-test-device',
    '00000000-0000-0000-0000-000000000001'
);

-- An unfinished inspection note is a separate shared document and must follow the same RLS rules.
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    (select value::uuid from stage3_workspace_document_context where key = 'workspace_id'),
    'inspection_note_draft', 7001,
    '{"id":7001,"hive_id":7001,"notes":"Unfinished field notes","recorded_at":1000,"updated_at":1000}',
    1000, false, 'sql-test-device', '00000000-0000-0000-0000-000000000001'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
do $member_assertions$
declare visible_count integer;
begin
    select count(*) into visible_count from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and entity_type = 'hive' and entity_id = 7001;
    if visible_count <> 1 then
        raise exception 'Workspace member could not read shared hive record';
    end if;

    select count(*) into visible_count from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and entity_type = 'inspection_note_draft' and entity_id = 7001
      and payload::jsonb ->> 'notes' = 'Unfinished field notes';
    if visible_count <> 1 then
        raise exception 'Workspace member could not read an unfinished inspection note';
    end if;
end;
$member_assertions$;

update public.beekeep_workspace_documents
set payload = '{"id":7001,"number":"Edited by team member"}',
    updated_at = 2000,
    updated_by = '00000000-0000-0000-0000-000000000002'
where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
  and entity_type = 'hive' and entity_id = 7001;

update public.beekeep_workspace_documents
set payload = '{"id":7001,"hive_id":7001,"notes":"Edited by team member","recorded_at":1000,"updated_at":2000}',
    updated_at = 2000,
    updated_by = '00000000-0000-0000-0000-000000000002'
where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
  and entity_type = 'inspection_note_draft' and entity_id = 7001;

-- Workspace owner changes the invited user's role to viewer.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select public.beekeep_update_workspace_member_role(
    (select value::uuid from stage3_workspace_document_context where key = 'workspace_id'),
    '00000000-0000-0000-0000-000000000002',
    'viewer'
);

-- Seed private cloud records with one key that collides with an existing team hive.
insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
values
    ('00000000-0000-0000-0000-000000000001', 'apiary', 7500,
     '{"id":7500,"name":"Personal apiary to copy"}', 5000, false, 'owner-device'),
    ('00000000-0000-0000-0000-000000000001', 'hive', 7501,
     '{"id":7501,"number":"Personal hive to copy","apiary":"Personal apiary to copy"}', 5000, false, 'owner-device'),
    ('00000000-0000-0000-0000-000000000001', 'event', 7502,
     '{"id":7502,"hiveId":7501,"title":"Personal event to copy"}', 5000, false, 'owner-device'),
    ('00000000-0000-0000-0000-000000000001', 'hive', 7001,
     '{"id":7001,"number":"Conflicting private hive"}', 5000, false, 'owner-device');

-- A key collision must abort the whole copy and must not overwrite the existing team hive.
do $copy_conflict_assertions$
declare denied boolean := false; team_payload text;
begin
    begin
        perform * from public.beekeep_copy_personal_documents_to_workspace(
            (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
        );
    exception when unique_violation then
        denied := true;
    end;
    if not denied then
        raise exception 'Personal data copy should refuse duplicate record keys';
    end if;

    if exists (
        select 1 from public.beekeep_workspace_documents
        where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
          and entity_type in ('apiary', 'event') and entity_id in (7500, 7502)
    ) then
        raise exception 'Failed personal-data copy partially inserted records';
    end if;

    select payload into team_payload from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and entity_type = 'hive' and entity_id = 7001;
    if team_payload <> '{"id":7001,"number":"Edited by team member"}' then
        raise exception 'Personal-data copy overwrote an existing team record';
    end if;
end;
$copy_conflict_assertions$;

-- Remove the conflicting fixture as the database owner: authenticated clients must not
-- receive direct delete permission on the private per-user documents table.
reset role;
delete from public.beekeep_documents
where user_id = auth.uid() and entity_type = 'hive' and entity_id = 7001;
set role authenticated;

-- The owner can copy their private records once conflicting keys are removed.
select * from public.beekeep_copy_personal_documents_to_workspace(
    (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
);

do $copy_success_assertions$
declare copied_rows integer;
begin
    select count(*) into copied_rows
    from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and (entity_type, entity_id) in (('apiary', 7500), ('hive', 7501), ('event', 7502))
      and updated_by = auth.uid();
    if copied_rows <> 3 then
        raise exception 'Expected 3 private records to be copied atomically, got %', copied_rows;
    end if;
end;
$copy_success_assertions$;

-- Viewers can read but cannot insert, update, or import personal records into the team.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
do $viewer_assertions$
declare visible_count integer; affected integer; denied boolean := false;
begin
    select count(*) into visible_count from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and entity_type = 'hive' and entity_id = 7001;
    if visible_count <> 1 then
        raise exception 'Viewer could not read shared record';
    end if;

    select count(*) into visible_count from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and entity_type = 'inspection_note_draft' and entity_id = 7001;
    if visible_count <> 1 then
        raise exception 'Viewer could not read a shared inspection note';
    end if;

    update public.beekeep_workspace_documents
    set payload = '{"id":7001,"number":"Viewer must not edit"}',
        updated_at = 3000,
        updated_by = '00000000-0000-0000-0000-000000000002'
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and entity_type = 'hive' and entity_id = 7001;
    get diagnostics affected = row_count;
    if affected <> 0 then
        raise exception 'Viewer unexpectedly updated a shared record';
    end if;

    update public.beekeep_workspace_documents
    set payload = '{"id":7001,"hive_id":7001,"notes":"Viewer must not edit","updated_at":3000}',
        updated_at = 3000,
        updated_by = '00000000-0000-0000-0000-000000000002'
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
      and entity_type = 'inspection_note_draft' and entity_id = 7001;
    get diagnostics affected = row_count;
    if affected <> 0 then
        raise exception 'Viewer unexpectedly updated a shared inspection note';
    end if;

    begin
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
        ) values (
            (select value::uuid from stage3_workspace_document_context where key = 'workspace_id'),
            'hive', 7004, '{"id":7004}', 3000, false, 'viewer-device',
            '00000000-0000-0000-0000-000000000002'
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Viewer unexpectedly inserted a shared record';
    end if;

    denied := false;
    begin
        perform * from public.beekeep_copy_personal_documents_to_workspace(
            (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Viewer unexpectedly copied personal records into a team workspace';
    end if;
end;
$viewer_assertions$;

-- A non-member cannot read the workspace's records or insert a record.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
do $non_member_assertions$
declare visible_count integer; denied boolean := false;
begin
    select count(*) into visible_count from public.beekeep_workspace_documents
    where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id');
    if visible_count <> 0 then
        raise exception 'Non-member could read a shared workspace record';
    end if;

    begin
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
        ) values (
            (select value::uuid from stage3_workspace_document_context where key = 'workspace_id'),
            'hive', 7002, '{"id":7002}', 2000, false, 'unauthorized-device',
            '00000000-0000-0000-0000-000000000003'
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Non-member unexpectedly inserted a shared record';
    end if;
end;
$non_member_assertions$;

-- Personal workspaces cannot be written through the shared-record table.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
do $personal_isolation_assertions$
declare personal_workspace uuid; denied boolean := false;
begin
    select id into personal_workspace
    from public.beekeep_workspaces
    where owner_user_id = auth.uid() and is_personal = true;

    begin
        insert into public.beekeep_workspace_documents (
            workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
        ) values (
            personal_workspace, 'hive', 7003, '{"id":7003}', 3000, false, 'personal-test-device',
            auth.uid()
        );
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'Personal workspace was unexpectedly writable through shared-record storage';
    end if;
end;
$personal_isolation_assertions$;

reset role;

-- Removing an account that last edited a shared record must not delete the team's record.
delete from auth.users where id = '00000000-0000-0000-0000-000000000002';

do $deleted_member_assertions$
begin
    if not exists (
        select 1 from public.beekeep_workspace_documents
        where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
          and entity_type = 'hive' and entity_id = 7001
          and updated_by is null
    ) then
        raise exception 'Deleting a member account removed the shared record or retained a dangling editor reference';
    end if;
end;
$deleted_member_assertions$;

select 'Stage 3A shared-record access control tests passed' as result;
