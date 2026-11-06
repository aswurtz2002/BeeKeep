-- Regression tests for server-managed Commercial access grants.
-- Run after 20261106080000_beekeep_admin_commercial_grants.sql.
begin;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000991', 'commercial-admin@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000992', 'commercial-grant-target@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000993', 'commercial-grant-expiring@example.test', '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000994', 'commercial-existing-paid@example.test',
        '{"beekeep_plan":"commercial_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

do $commercial_grant_assertions$
declare
    result_row record;
    target_id uuid := '00000000-0000-0000-0000-000000000992';
    expiring_id uuid := '00000000-0000-0000-0000-000000000993';
    actor_id uuid := '00000000-0000-0000-0000-000000000991';
    metadata jsonb;
begin
    if public.beekeep_user_has_active_commercial(target_id) then
        raise exception 'A Hobbyist account must not begin with Commercial access.';
    end if;
    if public.beekeep_server_base_hive_limit(target_id) <> 15
       or public.beekeep_resolved_apiary_limit(target_id) <> 3 then
        raise exception 'A Hobbyist account should retain the 15-hive / 3-apiary allowance before a grant.';
    end if;

    select * into result_row
      from public.beekeep_admin_manage_commercial_access(
        'lookup', 'COMMERCIAL-GRANT-TARGET@example.test', null, null, actor_id
      );
    if result_row.account_id <> target_id
       or result_row.is_active_commercial
       or result_row.has_admin_grant then
        raise exception 'Lookup should return the exact account and show no initial grant.';
    end if;

    select * into result_row
      from public.beekeep_admin_manage_commercial_access(
        'grant', 'commercial-grant-target@example.test', null,
        'Regression test: permanent Commercial grant', actor_id
      );
    if not result_row.is_active_commercial
       or not result_row.has_admin_grant
       or not result_row.admin_grant_active
       or result_row.admin_grant_expires_at is not null
       or result_row.plan_code <> 'commercial' then
        raise exception 'A permanent admin grant must resolve the target account to active Commercial.';
    end if;

    if public.beekeep_server_base_hive_limit(target_id) <> 10000
       or public.beekeep_resolved_apiary_limit(target_id) <> 1000 then
        raise exception 'A Commercial admin grant must unlock the 10,000-hive / 1,000-apiary limits.';
    end if;

    select raw_app_meta_data into metadata from auth.users where id = target_id;
    if metadata <> '{}'::jsonb then
        raise exception 'A manual Commercial grant must not rewrite the account billing metadata.';
    end if;

    select * into result_row
      from public.beekeep_admin_manage_commercial_access(
        'grant', 'commercial-grant-expiring@example.test', 7,
        'Regression test: temporary Commercial grant', actor_id
      );
    if not result_row.is_active_commercial
       or not result_row.admin_grant_active
       or result_row.admin_grant_expires_at is null
       or result_row.admin_grant_expires_at::timestamptz <= pg_catalog.now() then
        raise exception 'A duration grant should have a future expiry.';
    end if;

    update public.beekeep_admin_commercial_grants
       set expires_at = pg_catalog.now() - interval '1 second'
     where user_id = expiring_id;
    if public.beekeep_user_has_active_commercial(expiring_id) then
        raise exception 'An expired admin grant must stop providing Commercial access.';
    end if;

    select * into result_row
      from public.beekeep_admin_manage_commercial_access(
        'lookup', 'commercial-grant-expiring@example.test', null, null, actor_id
      );
    if result_row.is_active_commercial
       or not result_row.has_admin_grant
       or result_row.admin_grant_active then
        raise exception 'Lookup must distinguish an expired stored grant from active Commercial access.';
    end if;

    select * into result_row
      from public.beekeep_admin_manage_commercial_access(
        'revoke', 'commercial-grant-target@example.test', null, null, actor_id
      );
    if result_row.is_active_commercial
       or result_row.has_admin_grant
       or result_row.admin_grant_active
       or pg_catalog.jsonb_array_length(result_row.audit_history) < 2 then
        raise exception 'Revoking a manual grant must restore Hobbyist and retain grant/revoke audit history.';
    end if;

    select * into result_row
      from public.beekeep_admin_manage_commercial_access(
        'revoke', 'commercial-grant-expiring@example.test', null,
        'Regression test: revoke expired grant', actor_id
      );
    if result_row.has_admin_grant then
        raise exception 'Revoking an expired grant must remove its stored row.';
    end if;

    if public.beekeep_server_base_hive_limit(
        '00000000-0000-0000-0000-000000000994'
    ) <> 10000 then
        raise exception 'Existing active Commercial billing entitlements must remain unchanged.';
    end if;

    if has_function_privilege(
        'anon',
        'public.beekeep_admin_manage_commercial_access(text,text,integer,text,uuid)',
        'EXECUTE'
    ) or has_function_privilege(
        'authenticated',
        'public.beekeep_admin_manage_commercial_access(text,text,integer,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'Anonymous and authenticated clients must not execute the admin RPC.';
    end if;

    if not has_function_privilege(
        'service_role',
        'public.beekeep_admin_manage_commercial_access(text,text,integer,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'The protected Edge Function service-role client must execute the admin RPC.';
    end if;

    if has_table_privilege('authenticated', 'public.beekeep_admin_commercial_grants', 'SELECT')
       or has_table_privilege('authenticated', 'public.beekeep_admin_commercial_grant_audit', 'SELECT')
       or has_table_privilege('service_role', 'public.beekeep_admin_commercial_grants', 'SELECT')
       or has_table_privilege('service_role', 'public.beekeep_admin_commercial_grant_audit', 'SELECT') then
        raise exception 'Admin grant and audit tables must remain inaccessible through direct table grants.';
    end if;
end;
$commercial_grant_assertions$;

rollback;
select 'Admin-managed Commercial access grant tests passed' as result;
