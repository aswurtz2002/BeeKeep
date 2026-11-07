-- Backend foundation tests for trusted-session identity.
-- The policy gate is not applied by this foundation migration; these assertions
-- verify the shared predicate used by the following enforcement migration.

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000071', 'trusted-device-a@example.test', '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000072', 'trusted-device-b@example.test', '{}'::jsonb)
on conflict (id) do nothing;

insert into auth.sessions (id, user_id, created_at, updated_at, not_after)
values
    ('10000000-0000-0000-0000-000000000071', '00000000-0000-0000-0000-000000000071', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000072', '00000000-0000-0000-0000-000000000071', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000073', '00000000-0000-0000-0000-000000000072', now(), now(), now() + interval '30 days'),
    ('10000000-0000-0000-0000-000000000074', '00000000-0000-0000-0000-000000000071', now(), now(), now() - interval '1 day')
on conflict (id) do nothing;

insert into beekeep_private.trusted_devices (
    user_id, session_id, key_fingerprint, public_key, device_label, device_status
)
values (
    '00000000-0000-0000-0000-000000000071',
    '10000000-0000-0000-0000-000000000071',
    repeat('a', 64),
    repeat('A', 128),
    'Trusted test phone',
    'active'
)
on conflict (session_id) do nothing;

-- The per-account uniqueness rule prevents a second simultaneous active phone.
do $single_active_device$
begin
    begin
        insert into beekeep_private.trusted_devices (
            user_id, session_id, key_fingerprint, public_key, device_label
        )
        values (
            '00000000-0000-0000-0000-000000000071',
            '10000000-0000-0000-0000-000000000072',
            repeat('b', 64),
            repeat('B', 128),
            'Second test phone'
        );
        raise exception 'The database allowed two active devices for one BeeKeep account';
    exception
        when unique_violation then
            null;
    end;
end;
$single_active_device$;

do $private_registry_acl$
begin
    if has_schema_privilege('anon', 'beekeep_private', 'USAGE')
       or has_schema_privilege('authenticated', 'beekeep_private', 'USAGE')
       or has_table_privilege('anon', 'beekeep_private.trusted_devices', 'SELECT')
       or has_table_privilege('authenticated', 'beekeep_private.trusted_devices', 'SELECT')
       or has_table_privilege('service_role', 'beekeep_private.trusted_devices', 'SELECT') then
        raise exception 'A client or service role has direct access to the private trusted-device registry';
    end if;
end;
$private_registry_acl$;

set role authenticated;

-- An authenticated token with a matching active installation is trusted.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000071","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000071"}',
    true
);
do $trusted_session$
begin
    if not public.beekeep_device_session_is_trusted() then
        raise exception 'A registered active device session was not recognized as trusted';
    end if;
    perform public.beekeep_require_trusted_device();
end;
$trusted_session$;

-- A second Auth session for the same user is not trusted merely because the
-- account ID matches.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000071","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000072"}',
    true
);
do $unregistered_session$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'An unregistered second session was incorrectly trusted';
    end if;
    begin
        perform public.beekeep_require_trusted_device();
        raise exception 'The required trusted-device guard did not reject an unknown session';
    exception
        when insufficient_privilege then
            if sqlerrm not like '%not approved%' then
                raise;
            end if;
    end;
end;
$unregistered_session$;

-- A session belonging to a different account cannot reuse the trusted session id.
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000072","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000071"}',
    true
);
do $wrong_account$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'A trusted session id was accepted for the wrong account';
    end if;
end;
$wrong_account$;

reset role;

-- A session beyond its Supabase not_after time cannot use a registry entry.
update beekeep_private.trusted_devices
set session_id = '10000000-0000-0000-0000-000000000074'
where user_id = '00000000-0000-0000-0000-000000000071';
set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000071","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000074"}',
    true
);
do $expired_auth_session$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'An expired Supabase Auth session was still considered trusted';
    end if;
end;
$expired_auth_session$;
reset role;

-- Revoking a trusted installation immediately makes the still-existing Auth
-- session untrusted at the database authorization layer.
update beekeep_private.trusted_devices
set device_status = 'revoked', revoked_at = now(), revoked_reason = 'security test'
where session_id = '10000000-0000-0000-0000-000000000074';

set role authenticated;
select set_config(
    'request.jwt.claims',
    '{"sub":"00000000-0000-0000-0000-000000000071","role":"authenticated","session_id":"10000000-0000-0000-0000-000000000074"}',
    true
);
do $revoked_session$
begin
    if public.beekeep_device_session_is_trusted() then
        raise exception 'A revoked installation was still considered trusted';
    end if;
end;
$revoked_session$;

reset role;
rollback;
