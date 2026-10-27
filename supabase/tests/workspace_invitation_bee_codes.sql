-- Regression coverage for Bee-prefixed six-digit workspace invitation codes.
-- Run after 20261027020000_beekeep_bee_invitation_codes.sql and the Ultimate entitlement migration.

insert into auth.users (id, email, raw_user_meta_data, raw_app_meta_data) values
    ('00000000-0000-0000-0000-000000000101', 'bee-code-owner@example.test', '{}'::jsonb,
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb),
    ('00000000-0000-0000-0000-000000000102', 'bee-code-worker@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000103', 'bee-code-wrong-email@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000104', 'bee-code-legacy@example.test', '{}'::jsonb, '{}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_user_meta_data = excluded.raw_user_meta_data,
    raw_app_meta_data = excluded.raw_app_meta_data;

create temporary table bee_invitation_test_context (
    key text primary key,
    value text not null
);
grant select, insert, update on bee_invitation_test_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000101', false);

insert into bee_invitation_test_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Bee code regression workspace');

insert into bee_invitation_test_context(key, value)
select 'invite_token', invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from bee_invitation_test_context where key = 'workspace_id'),
    'bee-code-worker@example.test',
    'member'
);

do $created_code_assertions$
declare
    workspace_value uuid := (select value::uuid from bee_invitation_test_context where key = 'workspace_id');
    token_value text := (select value from bee_invitation_test_context where key = 'invite_token');
    stored_hash text;
begin
    if token_value !~ '^Bee[0-9]{6}$' then
        raise exception 'Expected an invite code shaped like Bee123456, got %', token_value;
    end if;

    select i.token_hash into stored_hash
    from public.beekeep_workspace_invitations i
    where i.workspace_id = workspace_value
      and lower(i.email) = 'bee-code-worker@example.test'
      and i.status = 'pending';

    if stored_hash is null
       or stored_hash !~ '^[0-9a-f]{64}$'
       or stored_hash = token_value then
        raise exception 'New Bee invitation must be stored as a normalized hash, not raw code';
    end if;
end;
$created_code_assertions$;

-- A valid code must still be bound to the invited account email.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000103', false);
do $wrong_email_assertions$
declare
    rejected boolean := false;
    error_message text;
    code_value text := upper((select value from bee_invitation_test_context where key = 'invite_token'));
begin
    begin
        perform * from public.beekeep_accept_workspace_invitation(code_value);
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%Sign in with the email address the invitation was sent to%' then
            raise exception 'Unexpected wrong-email rejection: %', error_message;
        end if;
        rejected := true;
    end;
    if not rejected then raise exception 'Invitation unexpectedly accepted by an account with the wrong email'; end if;
end;
$wrong_email_assertions$;

-- The code in its displayed Bee123456 case must accept the normalized stored hash.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000102', false);
select * from public.beekeep_accept_workspace_invitation(
    (select value from bee_invitation_test_context where key = 'invite_token')
);

do $bee_accept_assertions$
declare workspace_value uuid := (select value::uuid from bee_invitation_test_context where key = 'workspace_id');
begin
    if not exists (
        select 1 from public.beekeep_workspace_members
        where workspace_id = workspace_value
          and user_id = '00000000-0000-0000-0000-000000000102'
          and role = 'member'
    ) then
        raise exception 'Uppercase Bee invitation code did not add the invited member';
    end if;
end;
$bee_accept_assertions$;

-- A code issued by the prior eight-character generator remains usable after this migration.
reset role;
insert into public.beekeep_workspace_invitations (
    workspace_id, email, role, token_hash, status, created_by, expires_at
) values (
    (select value::uuid from bee_invitation_test_context where key = 'workspace_id'),
    'bee-code-legacy@example.test',
    'viewer',
    encode(extensions.digest('ABCD2345', 'sha256'), 'hex'),
    'pending',
    '00000000-0000-0000-0000-000000000101',
    now() + interval '7 days'
);

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000104', false);
select * from public.beekeep_accept_workspace_invitation('abcd2345');

do $legacy_accept_assertions$
declare workspace_value uuid := (select value::uuid from bee_invitation_test_context where key = 'workspace_id');
begin
    if not exists (
        select 1 from public.beekeep_workspace_members
        where workspace_id = workspace_value
          and user_id = '00000000-0000-0000-0000-000000000104'
          and role = 'viewer'
    ) then
        raise exception 'A legacy eight-character invitation stopped working after the format migration';
    end if;
end;
$legacy_accept_assertions$;

reset role;
select 'Bee invitation code and legacy-compatibility tests passed' as result;
