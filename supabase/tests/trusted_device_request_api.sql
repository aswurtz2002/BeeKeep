-- Trusted-device request/approval/recovery SQL API regression tests.
-- This validates the transactional database operations. ECDSA signatures are
-- verified by the Edge Function and covered by its own tests in the next sub-stage.

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000081', 'device-api-a@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000082', 'device-api-b@example.test', '{}'::jsonb)
on conflict (id) do nothing;

insert into auth.sessions (id, user_id, created_at, updated_at, not_after)
values
    ('10000000-0000-0000-0000-000000000081', '00000000-0000-0000-0000-000000000081', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000082', '00000000-0000-0000-0000-000000000081', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000083', '00000000-0000-0000-0000-000000000081', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000084', '00000000-0000-0000-0000-000000000081', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000085', '00000000-0000-0000-0000-000000000082', now(), now(), now() + interval '30 days')
on conflict (id) do nothing;

create temporary table device_test_keys as
select
    replace(encode(convert_to(repeat('O', 100), 'UTF8'), 'base64'), E'\n', '') as old_key,
    replace(encode(convert_to(repeat('N', 100), 'UTF8'), 'base64'), E'\n', '') as replacement_key,
    replace(encode(convert_to(repeat('D', 100), 'UTF8'), 'base64'), E'\n', '') as denied_key,
    replace(encode(convert_to(repeat('F', 100), 'UTF8'), 'base64'), E'\n', '') as fresh_account_key;
grant select on device_test_keys to authenticated;

insert into beekeep_private.trusted_devices (
    user_id, session_id, key_fingerprint, public_key, device_label, device_status
)
select
    '00000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000081',
    encode(extensions.digest(pg_catalog.decode(k.old_key, 'base64'), 'sha256'), 'hex'),
    k.old_key,
    'Original trusted phone',
    'active'
from device_test_keys k;

create temporary table device_test_context (
    test_key text primary key,
    request_id uuid,
    request_mode text,
    challenge text,
    approval_challenge text
);
grant select, insert, update on device_test_context to authenticated;

-- Replacement phone creates a request while remaining untrusted.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000082"}',
    true
);
insert into device_test_context(test_key, request_id, request_mode, challenge)
select 'replacement', request_id, request_mode, challenge
from public.beekeep_device_begin_request(
    (select replacement_key from device_test_keys),
    'Replacement phone'
);

do $replacement_request$
begin
    if (select request_mode from device_test_context where test_key = 'replacement') <> 'replacement' then
        raise exception 'A second phone was not classified as a replacement request';
    end if;
    if (select length(challenge) from device_test_context where test_key = 'replacement') < 40 then
        raise exception 'The new phone was not given a strong registration challenge';
    end if;
end;
$replacement_request$;

-- Until the Edge Function verifies the key signature, the trusted phone must not see it.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000081"}',
    true
);
do $unverified_not_listed$
declare pending_count integer;
begin
    select count(*) into pending_count from public.beekeep_device_list_pending_requests();
    if pending_count <> 0 then
        raise exception 'An unverified registration challenge was listed for approval';
    end if;
end;
$unverified_not_listed$;

-- Mark the new key proof through the service-only verifier RPC to simulate
-- the Edge Function accepting a valid ECDSA signature.
reset role;
select public.beekeep_internal_device_mark_request_verified(
    (select request_id from device_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000082',
    encode(
        extensions.digest(
            convert_to((select challenge from device_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000081"}',
    true
);
do $trusted_phone_sees_verified_request$
declare pending_count integer;
begin
    select count(*) into pending_count from public.beekeep_device_list_pending_requests();
    if pending_count <> 1 then
        raise exception 'The trusted phone could not see a verified replacement request';
    end if;
end;
$trusted_phone_sees_verified_request$;

update device_test_context
set approval_challenge = public.beekeep_device_issue_approval_challenge(request_id)
where test_key = 'replacement';
reset role;

select public.beekeep_internal_device_approve_request(
    (select request_id from device_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000081',
    encode(
        extensions.digest(
            convert_to((select approval_challenge from device_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000082"}',
    true
);
do $approved_replacement$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The approved replacement phone did not become trusted';
    end if;
    if (select request_status from public.beekeep_device_get_request_status()) <> 'approved' then
        raise exception 'The replacement request was not marked approved';
    end if;
end;
$approved_replacement$;

select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000081"}',
    true
);
do $replaced_phone_revoked$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The previous phone remained trusted after replacement approval';
    end if;
end;
$replaced_phone_revoked$;

reset role;

-- A denied request does not revoke the currently trusted phone.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000083"}',
    true
);
insert into device_test_context(test_key, request_id, request_mode, challenge)
select 'denied', request_id, request_mode, challenge
from public.beekeep_device_begin_request(
    (select denied_key from device_test_keys),
    'Unapproved phone'
);
reset role;

select public.beekeep_internal_device_mark_request_verified(
    (select request_id from device_test_context where test_key = 'denied'),
    '00000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000083',
    encode(
        extensions.digest(
            convert_to((select challenge from device_test_context where test_key = 'denied'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000082"}',
    true
);
update device_test_context
set approval_challenge = public.beekeep_device_issue_approval_challenge(request_id)
where test_key = 'denied';
reset role;

select public.beekeep_internal_device_deny_request(
    (select request_id from device_test_context where test_key = 'denied'),
    '00000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000082',
    encode(
        extensions.digest(
            convert_to((select approval_challenge from device_test_context where test_key = 'denied'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000082"}',
    true
);
do $denied_does_not_revoke$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'Denying a replacement request revoked the existing trusted phone';
    end if;
end;
$denied_does_not_revoke$;

select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000083"}',
    true
);
do $denied_device_not_trusted$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'A denied phone became trusted';
    end if;
    if (select request_status from public.beekeep_device_get_request_status()) <> 'denied' then
        raise exception 'The request was not marked denied';
    end if;
end;
$denied_device_not_trusted$;
reset role;

-- Re-authenticating on the same installation can rebind its session by proving
-- possession of the same private key, without asking another phone to approve.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000084"}',
    true
);
insert into device_test_context(test_key, request_id, request_mode, challenge)
select 'rebind', request_id, request_mode, challenge
from public.beekeep_device_begin_request(
    (select replacement_key from device_test_keys),
    'Same trusted phone'
);
reset role;

do $rebind_mode$
begin
    if (select request_mode from device_test_context where test_key = 'rebind') <> 'same_device_rebind' then
        raise exception 'The same trusted installation was not classified as a rebind';
    end if;
end;
$rebind_mode$;

select public.beekeep_internal_device_mark_request_verified(
    (select request_id from device_test_context where test_key = 'rebind'),
    '00000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000084',
    encode(
        extensions.digest(
            convert_to((select challenge from device_test_context where test_key = 'rebind'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);
select public.beekeep_internal_device_rebind_session(
    (select request_id from device_test_context where test_key = 'rebind'),
    '00000000-0000-0000-0000-000000000081',
    '10000000-0000-0000-0000-000000000084',
    encode(
        extensions.digest(
            convert_to((select challenge from device_test_context where test_key = 'rebind'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000081","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000084"}',
    true
);
do $rebind_new_session$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The trusted installation could not rebind to its new Supabase session';
    end if;
end;
$rebind_new_session$;

-- First enrollment uses the email-recovery server operation after the Edge Function
-- verifies a recent email OTP. This SQL test simulates that verified Edge Function call.
reset role;
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000082","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000085"}',
    true
);
insert into device_test_context(test_key, request_id, request_mode, challenge)
select 'first_device', request_id, request_mode, challenge
from public.beekeep_device_begin_request(
    (select fresh_account_key from device_test_keys),
    'First trusted phone'
);

do $first_device_mode$
begin
    if (select request_mode from device_test_context where test_key = 'first_device') <> 'first_device_setup' then
        raise exception 'A first-time account was not classified for email verification';
    end if;
end;
$first_device_mode$;
reset role;

select public.beekeep_internal_device_mark_request_verified(
    (select request_id from device_test_context where test_key = 'first_device'),
    '00000000-0000-0000-0000-000000000082',
    '10000000-0000-0000-0000-000000000085',
    encode(
        extensions.digest(
            convert_to((select challenge from device_test_context where test_key = 'first_device'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);
select public.beekeep_internal_device_recover_request(
    (select request_id from device_test_context where test_key = 'first_device'),
    '00000000-0000-0000-0000-000000000082',
    '10000000-0000-0000-0000-000000000085',
    encode(
        extensions.digest(
            convert_to((select challenge from device_test_context where test_key = 'first_device'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000082","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000085"}',
    true
);
do $first_device_trusted$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The email-verified first phone did not become trusted';
    end if;
end;
$first_device_trusted$;

reset role;
rollback;
