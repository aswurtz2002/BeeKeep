-- Server-side trusted-device enforcement regression tests.
-- Run after the trusted-device registry/API and coordinated enforcement migration.

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000091', 'device-enforcement-owner@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000092', 'device-enforcement-member@example.test', '{}'::jsonb)
on conflict (id) do nothing;

insert into auth.sessions (id, user_id, created_at, updated_at, not_after)
values
    ('10000000-0000-0000-0000-000000000091', '00000000-0000-0000-0000-000000000091', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000092', '00000000-0000-0000-0000-000000000091', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000093', '00000000-0000-0000-0000-000000000092', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000094', '00000000-0000-0000-0000-000000000091', now(), now(), now() + interval '30 days')
on conflict (id) do nothing;

-- The team owner needs an active Commercial entitlement for the existing
-- workspace-owner triggers and the same checks used by production RPCs.
update auth.users
set raw_app_meta_data = coalesce(raw_app_meta_data, '{}'::jsonb) ||
    jsonb_build_object(
        'beekeep_plan', 'commercial_monthly',
        'beekeep_subscription_expires_at', (now() + interval '30 days')::text
    )
where id = '00000000-0000-0000-0000-000000000091';

-- Owner's current phone and the invited workspace member's own current phone.
insert into beekeep_private.trusted_devices (
    user_id, session_id, key_fingerprint, public_key, device_label, device_status, revoked_at
)
values
    ('00000000-0000-0000-0000-000000000091', '10000000-0000-0000-0000-000000000091',
     repeat('1', 64), repeat('A', 128), 'Owner trusted phone', 'active', null),
    ('00000000-0000-0000-0000-000000000092', '10000000-0000-0000-0000-000000000093',
     repeat('2', 64), repeat('B', 128), 'Member trusted phone', 'active', null),
    ('00000000-0000-0000-0000-000000000091', '10000000-0000-0000-0000-000000000094',
     repeat('3', 64), repeat('C', 128), 'Old replaced phone', 'replaced', now())
on conflict (session_id) do nothing;

insert into public.beekeep_workspaces (id, name, owner_user_id, is_personal)
values (
    '20000000-0000-0000-0000-000000000091',
    'Trusted Device Security Test Team',
    '00000000-0000-0000-0000-000000000091',
    false
)
on conflict (id) do nothing;

insert into public.beekeep_workspace_members (workspace_id, user_id, role)
values
    ('20000000-0000-0000-0000-000000000091', '00000000-0000-0000-0000-000000000091', 'owner'),
    ('20000000-0000-0000-0000-000000000091', '00000000-0000-0000-0000-000000000092', 'member')
on conflict (workspace_id, user_id) do nothing;

-- Existing document triggers validate the authenticated owner even during test seeding.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000091"}',
    true
);

insert into public.beekeep_documents (user_id, entity_type, entity_id, payload, updated_at, deleted, device_id)
values
    ('00000000-0000-0000-0000-000000000091', 'hive', 9101, '{"id":9101,"number":"Private owner hive"}', 1000, false, 'trusted-device-test')
on conflict (user_id, entity_type, entity_id) do nothing;

insert into public.beekeep_workspace_documents (
    workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
)
values (
    '20000000-0000-0000-0000-000000000091',
    'hive', 9301, '{"id":9301,"number":"Shared team hive"}',
    1000, false, 'trusted-device-test', '00000000-0000-0000-0000-000000000091'
)
on conflict (workspace_id, entity_type, entity_id) do nothing;

insert into storage.objects (bucket_id, name, owner_id)
values (
    'inspection-photos',
    '00000000-0000-0000-0000-000000000091/trusted-device-test.jpg',
    '00000000-0000-0000-0000-000000000091'
);

-- The restrictive gate must be additive: it cannot remove any pre-existing ownership
-- and workspace policies, and every public RLS table must receive exactly one gate.
do $policy_inventory$
declare
    table_count integer;
    gate_count integer;
    original_policy_count integer;
begin
    select count(*) into table_count
    from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind = 'r' and c.relrowsecurity;

    select count(*) into gate_count
    from pg_policy p
    join pg_class c on c.oid = p.polrelid
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and p.polname = 'beekeep_trusted_device_required'
      and not p.polpermissive
      and p.polroles = array[(select oid from pg_roles where rolname = 'authenticated')]::oid[];

    select count(*) into original_policy_count
    from pg_policy p
    join pg_class c on c.oid = p.polrelid
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public'
      and p.polname <> 'beekeep_trusted_device_required';

    if table_count <> 19 or gate_count <> table_count then
        raise exception 'Expected one restrictive trusted-device policy on all 19 RLS tables (RLS %, gate %)',
            table_count, gate_count;
    end if;
    if original_policy_count < 19 then
        raise exception 'The migration unexpectedly removed existing ownership/workspace policies';
    end if;
    if not exists (
        select 1 from pg_policy p
        join pg_class c on c.oid = p.polrelid
        join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'storage' and c.relname = 'objects'
          and p.polname = 'beekeep_trusted_device_required'
          and not p.polpermissive
    ) then
        raise exception 'The inspection photo Storage policy is missing its trusted-device gate';
    end if;
end;
$policy_inventory$;

create temporary table trusted_device_enforcement_context (
    request_id uuid,
    request_mode text
);
grant select, insert, update on trusted_device_enforcement_context to authenticated;

set role authenticated;

-- The owner's registered session can read personal records, its team records,
-- and its own inspection photos. Existing workspace roles still apply.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000091"}',
    true
);

do $trusted_owner$
declare
    row_count integer;
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The registered owner phone was not trusted';
    end if;

    select count(*) into row_count from public.beekeep_documents
    where user_id = auth.uid() and entity_type = 'hive' and entity_id = 9101;
    if row_count <> 1 then raise exception 'Trusted owner could not read its personal hive'; end if;

    select count(*) into row_count from public.beekeep_workspace_documents
    where workspace_id = '20000000-0000-0000-0000-000000000091' and entity_id = 9301;
    if row_count <> 1 then raise exception 'Trusted owner could not read a team hive'; end if;

    select count(*) into row_count from storage.objects
    where bucket_id = 'inspection-photos' and owner_id::text = auth.uid()::text;
    if row_count <> 1 then raise exception 'Trusted owner could not read its inspection photo'; end if;

    if not public.has_beekeep_workspace_role(
        '20000000-0000-0000-0000-000000000091', array['owner']::text[]
    ) then
        raise exception 'Trusted owner lost the workspace owner role';
    end if;
end;
$trusted_owner$;

-- A teammate's own trusted phone remains valid and can read shared workspace
-- records, but not the owner's personal hive records.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000092","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000093"}',
    true
);

do $trusted_member$
declare
    row_count integer;
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The workspace member''s own trusted phone was not recognized';
    end if;

    select count(*) into row_count from public.beekeep_workspace_documents
    where workspace_id = '20000000-0000-0000-0000-000000000091' and entity_id = 9301;
    if row_count <> 1 then raise exception 'Trusted workspace member could not read shared data'; end if;

    select count(*) into row_count from public.beekeep_documents
    where user_id = '00000000-0000-0000-0000-000000000091';
    if row_count <> 0 then raise exception 'Workspace member read another user''s personal data'; end if;
end;
$trusted_member$;

-- A second, unregistered login session for the owner is denied even though the
-- user ID and password are valid. Local data is not part of this server test.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000092"}',
    true
);

do $untrusted_session$
declare
    row_count integer;
    denied boolean := false;
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The second owner phone was incorrectly trusted';
    end if;

    select count(*) into row_count from public.beekeep_documents
    where user_id = auth.uid() and entity_type = 'hive';
    if row_count <> 0 then raise exception 'An untrusted phone read personal hive data'; end if;

    select count(*) into row_count from storage.objects
    where bucket_id = 'inspection-photos' and owner_id::text = auth.uid()::text;
    if row_count <> 0 then raise exception 'An untrusted phone read inspection photos'; end if;

    denied := false;
    row_count := 0;
    begin
        select count(*) into row_count from public.beekeep_workspace_documents
        where workspace_id = '20000000-0000-0000-0000-000000000091';
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied and row_count <> 0 then
        raise exception 'An untrusted phone read shared workspace records';
    end if;

    denied := false;
    begin
        perform public.beekeep_device_list_pending_requests();
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then
        raise exception 'An untrusted phone could list pending approvals on the trusted phone';
    end if;

    begin
        insert into public.beekeep_documents (user_id, entity_type, entity_id, payload, updated_at, device_id)
        values (auth.uid(), 'hive', 9102, '{"id":9102}', 2000, 'untrusted-phone');
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then raise exception 'An untrusted phone inserted a personal hive'; end if;

    denied := false;
    begin
        insert into storage.objects (bucket_id, name, owner_id)
        values ('inspection-photos', auth.uid()::text || '/untrusted.jpg', auth.uid());
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then raise exception 'An untrusted phone uploaded an inspection photo'; end if;

    denied := false;
    begin
        perform * from public.beekeep_create_workspace('Untrusted Device Must Not Create Team');
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then raise exception 'An untrusted phone called a SECURITY DEFINER workspace RPC'; end if;

    denied := false;
    begin
        perform public.beekeep_my_active_ultimate_entitlement();
    exception when insufficient_privilege then
        denied := true;
    end;
    if not denied then raise exception 'An untrusted phone called an entitlement SECURITY DEFINER RPC'; end if;

    -- The device enrolment API is intentionally outside the general cloud-data gate:
    -- otherwise a second phone could never request approval or email recovery.
    insert into trusted_device_enforcement_context(request_id, request_mode)
    select request_id, request_mode
    from public.beekeep_device_begin_request(
        replace(encode(convert_to(repeat('Z', 128), 'UTF8'), 'base64'), E'\n', ''),
        'Untrusted replacement test phone'
    );
    if (select request_mode from trusted_device_enforcement_context limit 1) <> 'replacement' then
        raise exception 'Untrusted phone could not start the replacement verification flow';
    end if;
end;
$untrusted_session$;

-- A replaced installation stays denied even if its original Auth session has not
-- yet expired or been physically signed out.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000094"}',
    true
);
do $replaced_session$
declare row_count integer;
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The replaced phone still passed the trusted-session predicate';
    end if;
    select count(*) into row_count from public.beekeep_documents
    where user_id = auth.uid();
    if row_count <> 0 then raise exception 'The replaced phone read personal cloud records'; end if;
end;
$replaced_session$;

-- A trusted user can still list its workspaces through the guarded RPC.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000091"}',
    true
);
do $trusted_rpc$
declare row_count integer;
begin
    select count(*) into row_count from public.beekeep_list_workspaces()
    where id = '20000000-0000-0000-0000-000000000091';
    if row_count <> 1 then raise exception 'Trusted owner could not list its team workspace through the guarded RPC'; end if;
end;
$trusted_rpc$;

reset role;

-- Username availability remains a safe public onboarding check for anonymous users.
set role anon;
select set_config('request.jwt.claims', '{"role":"anon"}', true);
do $anonymous_username_check$
begin
    perform * from public.beekeep_is_username_available('device_test_username');
end;
$anonymous_username_check$;
reset role;

rollback;
