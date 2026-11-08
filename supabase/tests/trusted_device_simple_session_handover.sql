-- Regression coverage for the simplified single-session device handover.
-- No real accounts or records are retained; all fixtures are rolled back.
begin;

insert into auth.users (id, email, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000101', 'simple-replace@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000102', 'simple-deny@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000103', 'simple-first@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000104', 'simple-recovery@example.test', '{}'::jsonb)
on conflict (id) do nothing;

insert into auth.sessions (id, user_id, created_at, updated_at, not_after)
values
    ('10000000-0000-0000-0000-000000000101', '00000000-0000-0000-0000-000000000101', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000102', '00000000-0000-0000-0000-000000000101', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000103', '00000000-0000-0000-0000-000000000102', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000104', '00000000-0000-0000-0000-000000000102', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000105', '00000000-0000-0000-0000-000000000103', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000106', '00000000-0000-0000-0000-000000000104', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000107', '00000000-0000-0000-0000-000000000104', now(), now(), now() + interval '30 days')
on conflict (id) do nothing;

insert into beekeep_private.trusted_devices (
    user_id, session_id, key_fingerprint, public_key, device_label, device_status
)
values
    ('00000000-0000-0000-0000-000000000101', '10000000-0000-0000-0000-000000000101', repeat('a', 64), repeat('A', 128), 'Old trusted phone', 'active'),
    ('00000000-0000-0000-0000-000000000102', '10000000-0000-0000-0000-000000000103', repeat('b', 64), repeat('B', 128), 'Trusted phone for deny test', 'active'),
    ('00000000-0000-0000-0000-000000000104', '10000000-0000-0000-0000-000000000106', repeat('c', 64), repeat('C', 128), 'Trusted phone for recovery test', 'active')
on conflict (session_id) do nothing;

create temporary table simple_handover_test_ids (
    scenario text primary key,
    request_id uuid not null
);
grant select, insert, update on simple_handover_test_ids to authenticated;

-- Device B starts a request using only its authenticated Supabase session.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000101","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000102"}',
    true
);
insert into simple_handover_test_ids
select 'replacement', request_id
from public.beekeep_device_simple_begin_request('Pixel replacement phone');

do $replacement_request_created$
declare request_status_value text;
begin
    select r.request_status into request_status_value
    from public.beekeep_device_get_request_status() r;
    if request_status_value <> 'pending' then
        raise exception 'The replacement phone did not enter the waiting state';
    end if;
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The replacement phone gained cloud access before approval';
    end if;
end;
$replacement_request_created$;

-- Device A explicitly approves. A stays active until B completes the handover.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000101","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000101"}',
    true
);
select public.beekeep_device_simple_approve_request(
    (select request_id from simple_handover_test_ids where scenario = 'replacement')
);

do $approval_waits_for_new_phone$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The old phone was signed out before the replacement completed its handover';
    end if;
end;
$approval_waits_for_new_phone$;

reset role;

-- Simulate the Edge Function beginning finalization, then Auth signOut(B JWT, others).
select public.beekeep_internal_device_simple_begin_finalization(
    (select request_id from simple_handover_test_ids where scenario = 'replacement'),
    '00000000-0000-0000-0000-000000000101',
    '10000000-0000-0000-0000-000000000102'
);
delete from auth.sessions
where user_id = '00000000-0000-0000-0000-000000000101'
  and id <> '10000000-0000-0000-0000-000000000102';

select public.beekeep_internal_device_simple_finalize_request(
    (select request_id from simple_handover_test_ids where scenario = 'replacement'),
    '00000000-0000-0000-0000-000000000101',
    '10000000-0000-0000-0000-000000000102'
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000101","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000101"}',
    true
);
do $old_phone_loses_access$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The replaced phone still has trusted cloud access';
    end if;
end;
$old_phone_loses_access$;

select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000101","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000102"}',
    true
);
do $new_phone_is_trusted$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The approved replacement phone did not become trusted';
    end if;
end;
$new_phone_is_trusted$;
reset role;

-- A trusted phone can deny a different pending request without affecting its own access.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000102","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000104"}',
    true
);
insert into simple_handover_test_ids
select 'denial', request_id
from public.beekeep_device_simple_begin_request('Unrecognized Android phone');

select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000102","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000103"}',
    true
);
select public.beekeep_device_simple_deny_request(
    (select request_id from simple_handover_test_ids where scenario = 'denial')
);
do $denial_does_not_replace_trusted_phone$
declare request_status_value text;
begin
    select r.request_status into request_status_value
    from public.beekeep_device_get_request_status() r
    where r.request_id = (select request_id from simple_handover_test_ids where scenario = 'denial');
    if request_status_value <> 'denied' then
        raise exception 'The pending replacement request was not denied';
    end if;
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'Denying a request removed the current phone trust';
    end if;
end;
$denial_does_not_replace_trusted_phone$;
reset role;

-- First-device setup uses the same finalization path after the Edge Function verifies email OTP.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000103","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000105"}',
    true
);
insert into simple_handover_test_ids
select 'first-device', request_id
from public.beekeep_device_simple_begin_request('First BeeKeep phone');
reset role;

select public.beekeep_internal_device_simple_recover_request(
    (select request_id from simple_handover_test_ids where scenario = 'first-device'),
    '00000000-0000-0000-0000-000000000103',
    '10000000-0000-0000-0000-000000000105'
);
select public.beekeep_internal_device_simple_begin_finalization(
    (select request_id from simple_handover_test_ids where scenario = 'first-device'),
    '00000000-0000-0000-0000-000000000103',
    '10000000-0000-0000-0000-000000000105'
);
select public.beekeep_internal_device_simple_finalize_request(
    (select request_id from simple_handover_test_ids where scenario = 'first-device'),
    '00000000-0000-0000-0000-000000000103',
    '10000000-0000-0000-0000-000000000105'
);
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000103","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000105"}',
    true
);
do $first_device_is_trusted$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'First-device email recovery did not activate its session';
    end if;
end;
$first_device_is_trusted$;
reset role;

-- Email recovery uses the same finalization and revokes the old trusted phone.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000104","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000107"}',
    true
);
insert into simple_handover_test_ids
select 'recovery', request_id
from public.beekeep_device_simple_begin_request('Recovery replacement phone');
reset role;

-- The Edge Function calls this service-only path only after verifying a fresh email OTP.
select public.beekeep_internal_device_simple_recover_request(
    (select request_id from simple_handover_test_ids where scenario = 'recovery'),
    '00000000-0000-0000-0000-000000000104',
    '10000000-0000-0000-0000-000000000107'
);
select public.beekeep_internal_device_simple_begin_finalization(
    (select request_id from simple_handover_test_ids where scenario = 'recovery'),
    '00000000-0000-0000-0000-000000000104',
    '10000000-0000-0000-0000-000000000107'
);
delete from auth.sessions
where user_id = '00000000-0000-0000-0000-000000000104'
  and id <> '10000000-0000-0000-0000-000000000107';
select public.beekeep_internal_device_simple_finalize_request(
    (select request_id from simple_handover_test_ids where scenario = 'recovery'),
    '00000000-0000-0000-0000-000000000104',
    '10000000-0000-0000-0000-000000000107'
);

-- Inspect the private registry as the migration-test owner, not as an application user.
do $recovery_leaves_one_active_phone$
declare active_count integer;
begin
    select count(*)::integer into active_count
    from beekeep_private.trusted_devices
    where user_id = '00000000-0000-0000-0000-000000000104'
      and device_status = 'active'
      and revoked_at is null;
    if active_count <> 1 then
        raise exception 'Email recovery did not leave exactly one active phone';
    end if;
end;
$recovery_leaves_one_active_phone$;

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000104","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000106"}',
    true
);
do $recovery_revokes_old_phone$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'Email recovery left the previous phone trusted';
    end if;
end;
$recovery_revokes_old_phone$;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000104","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000107"}',
    true
);
do $recovery_activates_new_phone$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'Email recovery did not activate the replacement phone';
    end if;
end;
$recovery_activates_new_phone$;
reset role;

rollback;
