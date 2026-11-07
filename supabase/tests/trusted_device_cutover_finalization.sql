-- Trusted-device cutover regression tests.
-- The Edge Function is responsible for ECDSA verification and Auth Admin sign-out.
-- This SQL test simulates the successful Auth "others" revocation by removing every
-- Auth session except the new phone's session before calling the final activation RPC.
begin;

insert into auth.users (id, email, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000091', 'cutover-approval@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000092', 'cutover-recovery@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000093', 'cutover-rebind@example.test', '{}'::jsonb)
on conflict (id) do nothing;

insert into auth.sessions (id, user_id, created_at, updated_at, not_after)
values
    ('10000000-0000-0000-0000-000000000091', '00000000-0000-0000-0000-000000000091', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000092', '00000000-0000-0000-0000-000000000091', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000093', '00000000-0000-0000-0000-000000000091', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000094', '00000000-0000-0000-0000-000000000092', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000095', '00000000-0000-0000-0000-000000000092', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000096', '00000000-0000-0000-0000-000000000093', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000097', '00000000-0000-0000-0000-000000000093', now(), now(), now() + interval '30 days')
on conflict (id) do nothing;

create temporary table cutover_test_keys as
select
    replace(encode(convert_to(repeat('A', 100), 'UTF8'), 'base64'), E'\n', '') as old_key,
    replace(encode(convert_to(repeat('B', 100), 'UTF8'), 'base64'), E'\n', '') as replacement_key,
    replace(encode(convert_to(repeat('C', 100), 'UTF8'), 'base64'), E'\n', '') as recovery_key;
grant select on cutover_test_keys to authenticated;

create temporary table cutover_test_context (
    test_key text primary key,
    request_id uuid,
    request_mode text,
    challenge text,
    approval_challenge text,
    finalization_challenge text
);
grant select, insert, update on cutover_test_context to authenticated;

insert into beekeep_private.trusted_devices (
    user_id, session_id, key_fingerprint, public_key, device_label, device_status
)
select
    '00000000-0000-0000-0000-000000000091',
    '10000000-0000-0000-0000-000000000091',
    encode(extensions.digest(pg_catalog.decode(k.old_key, 'base64'), 'sha256'), 'hex'),
    k.old_key,
    'Original trusted phone',
    'active'
from cutover_test_keys k;

insert into beekeep_private.trusted_devices (
    user_id, session_id, key_fingerprint, public_key, device_label, device_status
)
select
    '00000000-0000-0000-0000-000000000093',
    '10000000-0000-0000-0000-000000000096',
    encode(extensions.digest(pg_catalog.decode(k.replacement_key, 'base64'), 'sha256'), 'hex'),
    k.replacement_key,
    'Same physical phone',
    'active'
from cutover_test_keys k;

-- Approval flow: a second installation proves its key, and the trusted phone approves it.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000092"}',
    true
);
insert into cutover_test_context(test_key, request_id, request_mode, challenge)
select 'replacement', request_id, request_mode, challenge
from public.beekeep_device_begin_request(
    (select replacement_key from cutover_test_keys),
    'Replacement phone'
);
reset role;

select public.beekeep_internal_device_mark_request_verified(
    (select request_id from cutover_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000091',
    '10000000-0000-0000-0000-000000000092',
    encode(
        extensions.digest(
            convert_to((select challenge from cutover_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000091"}',
    true
);
update cutover_test_context
set approval_challenge = public.beekeep_device_issue_approval_challenge(request_id)
where test_key = 'replacement';
reset role;

select public.beekeep_internal_device_approve_request(
    (select request_id from cutover_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000091',
    '10000000-0000-0000-0000-000000000091',
    encode(
        extensions.digest(
            convert_to((select approval_challenge from cutover_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

-- Approval must fail closed: A is no longer trusted, and B is not active before finalization.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000091"}',
    true
);
do $old_phone_blocked_after_approval$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The old phone remained trusted after approval';
    end if;
end;
$old_phone_blocked_after_approval$;

select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000092"}',
    true
);
do $new_phone_waits_for_finalization$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'The new phone became trusted before session finalization';
    end if;
    if (select request_status from public.beekeep_device_get_request_status()) <> 'approved_pending_finalize' then
        raise exception 'The replacement request did not enter approved_pending_finalize';
    end if;
end;
$new_phone_waits_for_finalization$;

-- B receives a second challenge. A simulated first attempt is aborted and can be retried.
update cutover_test_context
set finalization_challenge = public.beekeep_device_issue_finalize_challenge(request_id)
where test_key = 'replacement';
reset role;

select public.beekeep_internal_device_begin_finalization(
    (select request_id from cutover_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000091',
    '10000000-0000-0000-0000-000000000092',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

select public.beekeep_internal_device_abort_finalization(
    (select request_id from cutover_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000091',
    '10000000-0000-0000-0000-000000000092',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000092"}',
    true
);
do $aborted_finalization_is_retryable$
begin
    if (select request_status from public.beekeep_device_get_request_status()) <> 'approved_pending_finalize' then
        raise exception 'An aborted finalization did not return to the retryable state';
    end if;
end;
$aborted_finalization_is_retryable$;

update cutover_test_context
set finalization_challenge = public.beekeep_device_issue_finalize_challenge(request_id)
where test_key = 'replacement';
reset role;

select public.beekeep_internal_device_begin_finalization(
    (select request_id from cutover_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000091',
    '10000000-0000-0000-0000-000000000092',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

-- Simulate Auth Admin signOut(B JWT, "others"): revoke every session but B's session.
delete from auth.sessions
where user_id = '00000000-0000-0000-0000-000000000091'
  and id <> '10000000-0000-0000-0000-000000000092';

select public.beekeep_internal_device_finalize_request(
    (select request_id from cutover_test_context where test_key = 'replacement'),
    '00000000-0000-0000-0000-000000000091',
    '10000000-0000-0000-0000-000000000092',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'replacement'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

-- Only B remains as a valid Auth session and active trusted device. Replays are idempotent.
do $replacement_finalized$
declare created_id uuid;
begin
    if exists (
        select 1 from auth.sessions
        where user_id = '00000000-0000-0000-0000-000000000091'
          and id <> '10000000-0000-0000-0000-000000000092'
    ) then
        raise exception 'An older Auth session survived the simulated others-scope revocation';
    end if;
    if not exists (
        select 1 from auth.sessions
        where id = '10000000-0000-0000-0000-000000000092'
          and user_id = '00000000-0000-0000-0000-000000000091'
    ) then
        raise exception 'The new phone Auth session was revoked instead of preserved';
    end if;
    if (select request_status from beekeep_private.device_access_requests
        where id = (select request_id from cutover_test_context where test_key = 'replacement')) <> 'approved' then
        raise exception 'The replacement request was not marked complete';
    end if;
    select public.beekeep_internal_device_finalize_request(
        (select request_id from cutover_test_context where test_key = 'replacement'),
        '00000000-0000-0000-0000-000000000091',
        '10000000-0000-0000-0000-000000000092',
        encode(
            extensions.digest(
                convert_to((select finalization_challenge from cutover_test_context where test_key = 'replacement'), 'UTF8'),
                'sha256'
            ),
            'hex'
        )
    ) into created_id;
    if created_id is null then
        raise exception 'A replay of a completed finalization did not return the active device';
    end if;
end;
$replacement_finalized$;

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000091","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000092"}',
    true
);
do $replacement_device_is_trusted$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'The new phone was not trusted after finalization';
    end if;
end;
$replacement_device_is_trusted$;
reset role;

-- Email recovery / first-device setup uses the identical finalization step.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000092","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000094"}',
    true
);
insert into cutover_test_context(test_key, request_id, request_mode, challenge)
select 'recovery', request_id, request_mode, challenge
from public.beekeep_device_begin_request(
    (select recovery_key from cutover_test_keys),
    'Email-recovery phone'
);
reset role;

select public.beekeep_internal_device_mark_request_verified(
    (select request_id from cutover_test_context where test_key = 'recovery'),
    '00000000-0000-0000-0000-000000000092',
    '10000000-0000-0000-0000-000000000094',
    encode(
        extensions.digest(
            convert_to((select challenge from cutover_test_context where test_key = 'recovery'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

select public.beekeep_internal_device_recover_request(
    (select request_id from cutover_test_context where test_key = 'recovery'),
    '00000000-0000-0000-0000-000000000092',
    '10000000-0000-0000-0000-000000000094',
    encode(
        extensions.digest(
            convert_to((select challenge from cutover_test_context where test_key = 'recovery'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000092","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000094"}',
    true
);
do $email_recovery_waits$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'Email recovery activated a device before finalization';
    end if;
    if (select request_status from public.beekeep_device_get_request_status()) <> 'approved_pending_finalize' then
        raise exception 'Email recovery did not enter the pending-finalization state';
    end if;
end;
$email_recovery_waits$;

update cutover_test_context
set finalization_challenge = public.beekeep_device_issue_finalize_challenge(request_id)
where test_key = 'recovery';
reset role;

select public.beekeep_internal_device_begin_finalization(
    (select request_id from cutover_test_context where test_key = 'recovery'),
    '00000000-0000-0000-0000-000000000092',
    '10000000-0000-0000-0000-000000000094',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'recovery'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);
delete from auth.sessions
where user_id = '00000000-0000-0000-0000-000000000092'
  and id <> '10000000-0000-0000-0000-000000000094';
select public.beekeep_internal_device_finalize_request(
    (select request_id from cutover_test_context where test_key = 'recovery'),
    '00000000-0000-0000-0000-000000000092',
    '10000000-0000-0000-0000-000000000094',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'recovery'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

-- Same-phone rebind also revokes the old session and activates only its new session.
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000093","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000097"}',
    true
);
insert into cutover_test_context(test_key, request_id, request_mode, challenge)
select 'rebind', request_id, request_mode, challenge
from public.beekeep_device_begin_request(
    (select replacement_key from cutover_test_keys),
    'Rebound same phone'
);
reset role;

do $same_phone_request_mode$
begin
    if (select request_mode from cutover_test_context where test_key = 'rebind') <> 'same_device_rebind' then
        raise exception 'The same physical installation was not classified as a session rebind';
    end if;
end;
$same_phone_request_mode$;

select public.beekeep_internal_device_mark_request_verified(
    (select request_id from cutover_test_context where test_key = 'rebind'),
    '00000000-0000-0000-0000-000000000093',
    '10000000-0000-0000-0000-000000000097',
    encode(
        extensions.digest(
            convert_to((select challenge from cutover_test_context where test_key = 'rebind'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);
select public.beekeep_internal_device_rebind_session(
    (select request_id from cutover_test_context where test_key = 'rebind'),
    '00000000-0000-0000-0000-000000000093',
    '10000000-0000-0000-0000-000000000097',
    encode(
        extensions.digest(
            convert_to((select challenge from cutover_test_context where test_key = 'rebind'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000093","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000097"}',
    true
);
do $same_phone_waits$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'Same-phone rebind activated the new session before finalization';
    end if;
end;
$same_phone_waits$;

update cutover_test_context
set finalization_challenge = public.beekeep_device_issue_finalize_challenge(request_id)
where test_key = 'rebind';
reset role;

select public.beekeep_internal_device_begin_finalization(
    (select request_id from cutover_test_context where test_key = 'rebind'),
    '00000000-0000-0000-0000-000000000093',
    '10000000-0000-0000-0000-000000000097',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'rebind'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);
delete from auth.sessions
where user_id = '00000000-0000-0000-0000-000000000093'
  and id <> '10000000-0000-0000-0000-000000000097';
select public.beekeep_internal_device_finalize_request(
    (select request_id from cutover_test_context where test_key = 'rebind'),
    '00000000-0000-0000-0000-000000000093',
    '10000000-0000-0000-0000-000000000097',
    encode(
        extensions.digest(
            convert_to((select finalization_challenge from cutover_test_context where test_key = 'rebind'), 'UTF8'),
            'sha256'
        ),
        'hex'
    )
);

-- Internal state-changing RPCs stay service-role-only; the new challenge RPC is
-- callable by the authenticated, not-yet-trusted request session.
do $cutover_rpc_acl$
begin
    if has_function_privilege(
        'authenticated',
        'public.beekeep_internal_device_finalize_request(uuid,uuid,uuid,text)',
        'EXECUTE'
    ) then
        raise exception 'Authenticated clients can call the internal device finalizer directly';
    end if;
    if not has_function_privilege(
        'authenticated',
        'public.beekeep_device_issue_finalize_challenge(uuid)',
        'EXECUTE'
    ) then
        raise exception 'The authenticated replacement phone cannot request its finalization challenge';
    end if;
end;
$cutover_rpc_acl$;

reset role;
rollback;
