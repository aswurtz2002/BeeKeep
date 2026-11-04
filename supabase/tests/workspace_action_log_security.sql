-- Owner-only workspace Actions foundation: RLS, retention, soft deletes, and isolation.
-- All fixture users, workspaces, documents, and log entries are rolled back.

begin;

do $grant_assertions$
declare
    log_privileges text[];
    settings_privileges text[];
begin
    select array_agg(privilege_type order by privilege_type)
      into log_privileges
      from information_schema.role_table_grants
     where table_schema = 'public'
       and table_name = 'beekeep_workspace_action_log'
       and grantee = 'authenticated';

    select array_agg(privilege_type order by privilege_type)
      into settings_privileges
      from information_schema.role_table_grants
     where table_schema = 'public'
       and table_name = 'beekeep_workspace_action_log_settings'
       and grantee = 'authenticated';

    if log_privileges is distinct from array['SELECT']::text[] then
        raise exception 'Expected only SELECT on the action log for authenticated, got %', log_privileges;
    end if;
    if settings_privileges is distinct from array['SELECT']::text[] then
        raise exception 'Expected only SELECT on retention settings for authenticated, got %', settings_privileges;
    end if;
    if exists (
        select 1 from information_schema.role_table_grants
        where table_schema = 'public'
          and table_name in ('beekeep_workspace_action_log', 'beekeep_workspace_action_log_settings')
          and grantee in ('anon', 'public')
    ) then
        raise exception 'Anonymous or PUBLIC table grants must not exist on workspace Actions tables';
    end if;
end;
$grant_assertions$;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
 ('00000000-0000-0000-0000-000000000301', 'action-log-owner@example.test',
  '{"beekeep_plan":"commercial_monthly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{"username":"LogOwner"}'::jsonb),
 ('00000000-0000-0000-0000-000000000302', 'action-log-admin@example.test', '{}'::jsonb, '{"username":"LogAdmin"}'::jsonb),
 ('00000000-0000-0000-0000-000000000303', 'action-log-member@example.test', '{}'::jsonb, '{"username":"LogMember"}'::jsonb),
 ('00000000-0000-0000-0000-000000000304', 'action-log-viewer@example.test', '{}'::jsonb, '{"username":"LogViewer"}'::jsonb),
 ('00000000-0000-0000-0000-000000000305', 'action-log-outsider@example.test', '{}'::jsonb, '{"username":"LogOutside"}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

create temporary table action_log_context (
    key text primary key,
    value text not null
);
grant select, insert, update on action_log_context to authenticated;

-- The workspace creation trigger should seed a default Keep setting.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000301', false);

insert into action_log_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Actions Log Security Test');

insert into action_log_context(key, value)
select 'admin_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'action-log-admin@example.test',
    'admin'
);

insert into action_log_context(key, value)
select 'member_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'action-log-member@example.test',
    'member'
);

insert into action_log_context(key, value)
select 'viewer_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'action-log-viewer@example.test',
    'viewer'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000302', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from action_log_context where key = 'admin_token')
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000303', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from action_log_context where key = 'member_token')
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000304', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from action_log_context where key = 'viewer_token')
);

-- Owner's workspace has a default Keep setting; personal workspaces are excluded.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000301', false);
do $default_setting_assertion$
declare
    workspace_value uuid := (select value::uuid from action_log_context where key = 'workspace_id');
    personal_id uuid;
begin
    if not exists (
        select 1 from public.beekeep_workspace_action_log_settings
        where workspace_id = workspace_value and retention_mode = 'keep'
    ) then
        raise exception 'New team workspace was not seeded with Keep retention';
    end if;

    select id into personal_id
    from public.beekeep_list_workspaces()
    where is_personal = true
    limit 1;

    if exists (
        select 1 from public.beekeep_workspace_action_log_settings
        where workspace_id = personal_id
    ) then
        raise exception 'Personal workspace unexpectedly received team Actions settings';
    end if;
end;
$default_setting_assertion$;

-- Seed one real workspace record and three log rows using the privileged server role.
insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'apiary', 99301,
    '{"id":99301,"name":"Keep this apiary"}',
    1000, false, 'action-log-test', '00000000-0000-0000-0000-000000000301'
);

reset role;
insert into public.beekeep_workspace_action_log (
    id, workspace_id, client_action_id, user_id, username,
    action_type, target_type, target_id, description, action_at
)
values
 ('00000000-0000-4000-8000-000000000311',
  (select value::uuid from action_log_context where key = 'workspace_id'),
  '00000000-0000-4000-8000-000000000321',
  '00000000-0000-0000-0000-000000000301', 'LogOwner',
  'apiary_added', 'apiary', '99301', 'Added apiary Keep this apiary',
  pg_catalog.clock_timestamp()),
 ('00000000-0000-4000-8000-000000000312',
  (select value::uuid from action_log_context where key = 'workspace_id'),
  '00000000-0000-4000-8000-000000000322',
  '00000000-0000-0000-0000-000000000303', 'LogMember',
  'hive_added', 'hive', '99302', 'Added hive Hive 12 to Apiary North',
  pg_catalog.clock_timestamp()),
 ('00000000-0000-4000-8000-000000000313',
  (select value::uuid from action_log_context where key = 'workspace_id'),
  '00000000-0000-4000-8000-000000000323',
  '00000000-0000-0000-0000-000000000303', 'LogMember',
  'mass_treatment', 'batch', '00000000-0000-4000-8000-000000000323',
  'Mass treatment applied to Apiary North (14 hives)',
  pg_catalog.clock_timestamp());

-- Only the workspace Owner can read the activity list and retention setting.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000301', false);
do $owner_read_assertion$
declare log_count integer;
begin
    select count(*) into log_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id');

    if log_count <> 4 then
        raise exception 'Owner expected to read 4 active log rows (including the trigger-generated Apiary insert), received %', log_count;
    end if;
    if not exists (
        select 1 from public.beekeep_workspace_action_log_settings
        where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id')
          and retention_mode = 'keep'
    ) then
        raise exception 'Owner could not read the workspace retention setting';
    end if;
end;
$owner_read_assertion$;

-- Owner can switch retention to expire_90_days and back to Keep.
select public.beekeep_workspace_action_log_set_retention(
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'expire_90_days'
);
select public.beekeep_workspace_action_log_set_retention(
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'keep'
);

-- The owner cannot write log rows directly; only the trusted server path may insert.
do $owner_direct_write_assertion$
declare insert_denied boolean := false; update_denied boolean := false; delete_denied boolean := false;
begin
    begin
        insert into public.beekeep_workspace_action_log (
            workspace_id, client_action_id, user_id, username,
            action_type, target_type, description, action_at
        ) values (
            (select value::uuid from action_log_context where key = 'workspace_id'),
            '00000000-0000-4000-8000-000000000399',
            auth.uid(), 'LogOwner', 'hive_added', 'hive', 'Forged row',
            pg_catalog.clock_timestamp()
        );
    exception when insufficient_privilege then insert_denied := true;
    end;

    begin
        update public.beekeep_workspace_action_log
           set description = 'Edited by client'
         where id = '00000000-0000-4000-8000-000000000311';
    exception when insufficient_privilege then update_denied := true;
    end;

    begin
        delete from public.beekeep_workspace_action_log
        where id = '00000000-0000-4000-8000-000000000311';
    exception when insufficient_privilege then delete_denied := true;
    end;

    if not insert_denied or not update_denied or not delete_denied then
        raise exception 'Owner had direct table write permissions; all writes must use server functions';
    end if;
end;
$owner_direct_write_assertion$;

-- Single-entry soft-delete changes the log row only and preserves the apiary document.
select public.beekeep_workspace_action_log_delete(
    '00000000-0000-4000-8000-000000000311'
);

reset role;
do $single_delete_assertion$
declare
    log_is_deleted boolean;
    business_record_exists boolean;
begin
    select deleted_at is not null into log_is_deleted
    from public.beekeep_workspace_action_log
    where id = '00000000-0000-4000-8000-000000000311';

    select exists (
        select 1 from public.beekeep_workspace_documents
        where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id')
          and entity_type = 'apiary'
          and entity_id = 99301
          and not deleted
    ) into business_record_exists;

    if not log_is_deleted then
        raise exception 'Individual delete did not set deleted_at';
    end if;
    if not business_record_exists then
        raise exception 'Deleting a log entry changed or deleted the real apiary record';
    end if;
end;
$single_delete_assertion$;

-- Admin, Member, Viewer, and outsider cannot read or delete workspace action rows.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000302', false);
do $admin_assertion$
declare visible_count integer; denied boolean := false;
begin
    select count(*) into visible_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id');
    if visible_count <> 0 then
        raise exception 'Admin unexpectedly read the owner-only action log';
    end if;

    begin
        perform public.beekeep_workspace_action_log_delete(
            '00000000-0000-4000-8000-000000000312'
        );
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Admin unexpectedly deleted an action-log entry'; end if;

    denied := false;
    begin
        perform public.beekeep_workspace_action_log_set_retention(
            (select value::uuid from action_log_context where key = 'workspace_id'),
            'expire_90_days'
        );
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Admin unexpectedly changed owner-only retention'; end if;
end;
$admin_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000303', false);
do $member_assertion$
declare visible_count integer; denied boolean := false;
begin
    select count(*) into visible_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id');
    if visible_count <> 0 then
        raise exception 'Member unexpectedly read the owner-only action log';
    end if;

    begin
        perform public.beekeep_workspace_action_log_delete_all(
            (select value::uuid from action_log_context where key = 'workspace_id')
        );
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Member unexpectedly deleted all action-log entries'; end if;
end;
$member_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000304', false);
do $viewer_assertion$
declare visible_count integer; denied boolean := false;
begin
    select count(*) into visible_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id');
    if visible_count <> 0 then
        raise exception 'Viewer unexpectedly read the owner-only action log';
    end if;

    begin
        perform public.beekeep_workspace_action_log_delete_all(
            (select value::uuid from action_log_context where key = 'workspace_id')
        );
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Viewer unexpectedly deleted all action-log entries'; end if;
end;
$viewer_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000305', false);
do $outsider_assertion$
declare visible_count integer; denied boolean := false;
begin
    select count(*) into visible_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id');
    if visible_count <> 0 then
        raise exception 'Non-member unexpectedly read the owner-only action log';
    end if;

    begin
        perform public.beekeep_workspace_action_log_delete_all(
            (select value::uuid from action_log_context where key = 'workspace_id')
        );
    exception when insufficient_privilege then denied := true;
    end;
    if not denied then raise exception 'Non-member unexpectedly deleted all action-log entries'; end if;
end;
$outsider_assertion$;

-- Owner-only Delete all soft-deletes the remaining entries and returns an exact count.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000301', false);
do $delete_all_assertion$
declare
    affected integer;
    active_count integer;
begin
    affected := public.beekeep_workspace_action_log_delete_all(
        (select value::uuid from action_log_context where key = 'workspace_id')
    );
    if affected <> 3 then
        raise exception 'Delete all expected to soft-delete 3 active rows, changed %', affected;
    end if;
end;
$delete_all_assertion$;

reset role;
do $all_deleted_assertion$
declare
    deleted_count integer;
    business_record_exists boolean;
begin
    select count(*) into deleted_count
    from public.beekeep_workspace_action_log
    where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id')
      and deleted_at is not null;

    select exists (
        select 1 from public.beekeep_workspace_documents
        where workspace_id = (select value::uuid from action_log_context where key = 'workspace_id')
          and entity_type = 'apiary' and entity_id = 99301 and not deleted
    ) into business_record_exists;

    if deleted_count <> 4 then
        raise exception 'Expected all 4 log entries to be soft-deleted, got %', deleted_count;
    end if;
    if not business_record_exists then
        raise exception 'Delete all changed the real apiary record';
    end if;
end;
$all_deleted_assertion$;

-- Retention expires entries by original action time, while Keep preserves old entries.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000301', false);
select public.beekeep_workspace_action_log_set_retention(
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'expire_90_days'
);
reset role;

insert into public.beekeep_workspace_action_log (
    id, workspace_id, client_action_id, user_id, username,
    action_type, target_type, description, action_at
)
values
 ('00000000-0000-4000-8000-000000000331',
  (select value::uuid from action_log_context where key = 'workspace_id'),
  '00000000-0000-4000-8000-000000000341',
  '00000000-0000-0000-0000-000000000303', 'LogMember',
  'inspection_created', 'inspection', 'Old inspection action',
  pg_catalog.clock_timestamp() - interval '91 days');

select public.beekeep_workspace_action_log_expire();

do $expire_assertion$
begin
    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where id = '00000000-0000-4000-8000-000000000331'
          and deleted_at is not null
    ) then
        raise exception '90-day retention did not soft-delete an action older than 90 days';
    end if;
end;
$expire_assertion$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000301', false);
select public.beekeep_workspace_action_log_set_retention(
    (select value::uuid from action_log_context where key = 'workspace_id'),
    'keep'
);

insert into public.beekeep_workspace_action_log (
    id, workspace_id, client_action_id, user_id, username,
    action_type, target_type, description, action_at
)
values
 ('00000000-0000-4000-8000-000000000332',
  (select value::uuid from action_log_context where key = 'workspace_id'),
  '00000000-0000-4000-8000-000000000342',
  '00000000-0000-0000-0000-000000000303', 'LogMember',
  'inspection_created', 'inspection', 'Old kept inspection action',
  pg_catalog.clock_timestamp() - interval '91 days');

reset role;
select public.beekeep_workspace_action_log_expire();

do $keep_assertion$
begin
    if not exists (
        select 1 from public.beekeep_workspace_action_log
        where id = '00000000-0000-4000-8000-000000000332'
          and deleted_at is null
    ) then
        raise exception 'Keep Workspace Entries did not preserve an old action';
    end if;
end;
$keep_assertion$;

-- Idempotency key is unique per workspace; duplicate offline replay cannot create a second row.
do $idempotency_assertion$
declare duplicate_denied boolean := false;
begin
    begin
        insert into public.beekeep_workspace_action_log (
            workspace_id, client_action_id, user_id, username,
            action_type, target_type, description, action_at
        )
        values (
            (select value::uuid from action_log_context where key = 'workspace_id'),
            '00000000-0000-4000-8000-000000000342',
            '00000000-0000-0000-0000-000000000303', 'LogMember',
            'inspection_created', 'inspection', 'Duplicate replay',
            pg_catalog.clock_timestamp()
        );
    exception when unique_violation then duplicate_denied := true;
    end;
    if not duplicate_denied then
        raise exception 'Duplicate client_action_id was unexpectedly accepted';
    end if;
end;
$idempotency_assertion$;

reset role;
rollback;

select 'Workspace action-log foundation security tests passed' as result;
