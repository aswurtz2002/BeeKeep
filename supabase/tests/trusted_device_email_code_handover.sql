-- Regression coverage for email-code single-session replacement.
-- Password/Google-authenticated session starts the request; only a verified OTP
-- session can be rebound and finalized. All fixtures are rolled back.
begin;

insert into auth.users (id, email, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000201', 'email-replace@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000202', 'email-first@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000203', 'email-passwordless@example.test', '{}'::jsonb)
on conflict (id) do nothing;

insert into auth.sessions (id, user_id, created_at, updated_at, not_after)
values
    -- Existing trusted phone, initial password sign-in, and verified email OTP session.
    ('20000000-0000-0000-0000-000000000201', '00000000-0000-0000-0000-000000000201', now(), now(), now() + interval '30 days'),
    ('20000000-0000-0000-0000-000000000202', '00000000-0000-0000-0000-000000000201', now(), now(), now() + interval '30 days'),
    ('20000000-0000-0000-0000-000000000203', '00000000-0000-0000-0000-000000000201', now(), now(), now() + interval '30 days'),
    -- First phone: password session then OTP-verified session.
    ('20000000-0000-0000-0000-000000000204', '00000000-0000-0000-0000-000000000202', now(), now(), now() + interval '30 days'),
    ('20000000-0000-0000-0000-000000000205', '00000000-0000-0000-0000-000000000202', now(), now(), now() + interval '30 days'),
    -- Account with an existing phone: passwordless OTP-only session must not begin replacement.
    ('20000000-0000-0000-0000-000000000206', '00000000-0000-0000-0000-000000000203', now(), now(), now() + interval '30 days'),
    ('20000000-0000-0000-0000-000000000207', '00000000-0000-0000-0000-000000000203', now(), now(), now() + interval '30 days')
on conflict (id) do nothing;

insert into beekeep_private.trusted_devices (
    user_id, session_id, key_fingerprint, public_key, device_label, device_status
)
values
    ('00000000-0000-0000-0000-000000000201', '20000000-0000-0000-0000-000000000201', null, null, 'Existing trusted phone', 'active'),
    ('00000000-0000-0000-0000-000000000203', '20000000-0000-0000-0000-000000000206', null, null, 'Passwordless test trusted phone', 'active')
on conflict (session_id) do nothing;

create temporary table email_handover_test_ids (
    scenario text primary key,
    request_id uuid not null
);
grant select, insert, update on email_handover_test_ids to authenticated;

-- New phone begins only after normal password sign-in. It has no trusted cloud access.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000201","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000202","amr":[{"method":"password","timestamp":1794670000}]}',
    true
);
insert into email_handover_test_ids
select 'replacement', request_id
from public.beekeep_device_simple_begin_request('Pixel email replacement');

do $password_session_starts_pending_request$
declare request_status_value text;
begin
    select r.request_status into request_status_value
    from public.beekeep_device_get_request_status() r;
    if request_status_value <> 'pending' then
        raise exception 'Password-authenticated phone did not create a pending email verification request';
    end if;
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The replacement phone gained cloud access before email verification';
    end if;
end;
$password_session_starts_pending_request$;
reset role;

-- Supabase Auth has verified the six-digit OTP and created a fresh session.
-- The Edge Function binds the request to that verified session before recovery/finalization.
select public.beekeep_internal_device_simple_bind_email_session(
    (select request_id from email_handover_test_ids where scenario = 'replacement'),
    '00000000-0000-0000-0000-000000000201',
    '20000000-0000-0000-0000-000000000203'
);
select public.beekeep_internal_device_simple_recover_request(
    (select request_id from email_handover_test_ids where scenario = 'replacement'),
    '00000000-0000-0000-0000-000000000201',
    '20000000-0000-0000-0000-000000000203'
);
-- A duplicate retry after a lost response must be harmless.
select public.beekeep_internal_device_simple_recover_request(
    (select request_id from email_handover_test_ids where scenario = 'replacement'),
    '00000000-0000-0000-0000-000000000201',
    '20000000-0000-0000-0000-000000000203'
);
select public.beekeep_internal_device_simple_begin_finalization(
    (select request_id from email_handover_test_ids where scenario = 'replacement'),
    '00000000-0000-0000-0000-000000000201',
    '20000000-0000-0000-0000-000000000203'
);

-- Simulate Auth admin.signOut(verified OTP JWT, 'others').
delete from auth.sessions
where user_id = '00000000-0000-0000-0000-000000000201'
  and id <> '20000000-0000-0000-0000-000000000203';

select public.beekeep_internal_device_simple_finalize_request(
    (select request_id from email_handover_test_ids where scenario = 'replacement'),
    '00000000-0000-0000-0000-000000000201',
    '20000000-0000-0000-0000-000000000203'
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000201","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000201"}',
    true
);
do $old_phone_is_revoked$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'Email replacement did not revoke the old trusted phone';
    end if;
end;
$old_phone_is_revoked$;

select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000201","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000202"}',
    true
);
do $pre_otp_password_session_is_revoked$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The pre-OTP password session remained trusted after replacement';
    end if;
end;
$pre_otp_password_session_is_revoked$;

select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000201","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000203"}',
    true
);
do $email_verified_phone_is_trusted$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The verified replacement phone did not become trusted';
    end if;
end;
$email_verified_phone_is_trusted$;
reset role;

-- Email OTP alone cannot start a replacement while another trusted phone is active.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000203","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000207","amr":[{"method":"otp","timestamp":1794670000}]}',
    true
);
do $passwordless_otp_cannot_replace$
begin
    begin
        perform * from public.beekeep_device_simple_begin_request('Passwordless replacement');
        raise exception 'Passwordless email OTP unexpectedly started a phone replacement';
    exception
        when insufficient_privilege then
            if position('password or Google' in sqlerrm) = 0 then
                raise;
            end if;
    end;
end;
$passwordless_otp_cannot_replace$;
reset role;

-- First phone setup follows the same OTP-verification/finalization route,
-- but is allowed because the account has no currently trusted device.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000202","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000204","amr":[{"method":"password","timestamp":1794670000}]}',
    true
);
insert into email_handover_test_ids
select 'first-device', request_id
from public.beekeep_device_simple_begin_request('First BeeKeep phone');
reset role;

select public.beekeep_internal_device_simple_bind_email_session(
    (select request_id from email_handover_test_ids where scenario = 'first-device'),
    '00000000-0000-0000-0000-000000000202',
    '20000000-0000-0000-0000-000000000205'
);
select public.beekeep_internal_device_simple_recover_request(
    (select request_id from email_handover_test_ids where scenario = 'first-device'),
    '00000000-0000-0000-0000-000000000202',
    '20000000-0000-0000-0000-000000000205'
);
select public.beekeep_internal_device_simple_begin_finalization(
    (select request_id from email_handover_test_ids where scenario = 'first-device'),
    '00000000-0000-0000-0000-000000000202',
    '20000000-0000-0000-0000-000000000205'
);
delete from auth.sessions
where user_id = '00000000-0000-0000-0000-000000000202'
  and id <> '20000000-0000-0000-0000-000000000205';
select public.beekeep_internal_device_simple_finalize_request(
    (select request_id from email_handover_test_ids where scenario = 'first-device'),
    '00000000-0000-0000-0000-000000000202',
    '20000000-0000-0000-0000-000000000205'
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000202","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000204"}',
    true
);
do $first_phone_password_session_is_revoked$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'First-device setup left the initial password session trusted';
    end if;
end;
$first_phone_password_session_is_revoked$;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000202","role":"authenticated","session_id":"20000000-0000-0000-0000-000000000205"}',
    true
);
do $first_phone_email_session_is_trusted$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The email-verified first phone did not become trusted';
    end if;
end;
$first_phone_email_session_is_trusted$;
reset role;

rollback;
