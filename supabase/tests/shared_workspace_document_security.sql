-- Stage 3A shared-record access control assertions.
-- Requires the Stage 1/2 schema, invitation functions, and Stage 3A migration.

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
end;
$member_assertions$;

update public.beekeep_workspace_documents
set payload = '{"id":7001,"number":"Edited by team member"}',
    updated_at = 2000,
    updated_by = '00000000-0000-0000-0000-000000000002'
where workspace_id = (select value::uuid from stage3_workspace_document_context where key = 'workspace_id')
  and entity_type = 'hive' and entity_id = 7001;

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
select 'Stage 3A shared-record access control tests passed' as result;
