-- Regression coverage for the protected support-admin hive-limit management RPC.
-- Run after 20261027062000_beekeep_per_account_hive_limit_overrides.sql and
-- 20261027110000_beekeep_admin_hive_limit_tool.sql on a disposable database.
begin;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000031', 'hive-admin-actor@example.test',
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb,
        '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000032', 'hive-admin-target@example.test',
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb,
        '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000033', 'hive-admin-free@example.test',
        '{}'::jsonb,
        '{}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

do $support_admin_assertions$
declare
    result_row record;
    denied boolean := false;
begin
    select * into result_row
      from public.beekeep_admin_manage_hive_limit_override(
        'lookup', 'HIVE-ADMIN-TARGET@example.test', null, null,
        '00000000-0000-0000-0000-000000000031'
      );
    if result_row.account_id <> '00000000-0000-0000-0000-000000000032'::uuid
       or result_row.account_email <> 'hive-admin-target@example.test'
       or result_row.is_active_ultimate is distinct from true
       or result_row.custom_hive_limit is not null then
        raise exception 'Admin lookup must return one exact active Ultimate account with no initial override.';
    end if;

    select * into result_row
      from public.beekeep_admin_manage_hive_limit_override(
        'set', 'hive-admin-target@example.test', 11000, 'Regression test: approved exception',
        '00000000-0000-0000-0000-000000000031'
      );
    if result_row.custom_hive_limit <> 11000
       or result_row.override_reason <> 'Regression test: approved exception' then
        raise exception 'Admin set should store the custom limit and reason.';
    end if;

    begin
        perform 1
          from public.beekeep_admin_manage_hive_limit_override(
            'set', 'hive-admin-free@example.test', 11000, 'Must be rejected',
            '00000000-0000-0000-0000-000000000031'
          );
        raise exception 'A non-Ultimate account must not accept a custom hive limit.';
    exception when invalid_parameter_value then
        null;
    end;

    begin
        perform 1
          from public.beekeep_admin_manage_hive_limit_override(
            'set', 'hive-admin-target@example.test', 9999, 'Below the allowed range',
            '00000000-0000-0000-0000-000000000031'
          );
        raise exception 'A custom hive limit below 10,000 must be rejected.';
    exception when invalid_parameter_value then
        null;
    end;

    select * into result_row
      from public.beekeep_admin_manage_hive_limit_override(
        'clear', 'hive-admin-target@example.test', null, null,
        '00000000-0000-0000-0000-000000000031'
      );
    if result_row.custom_hive_limit is not null then
        raise exception 'Clearing an override should restore the normal Ultimate cap.';
    end if;

    if has_function_privilege(
        'anon',
        'public.beekeep_admin_manage_hive_limit_override(text,text,integer,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'Anonymous users must not execute the admin RPC.';
    end if;
    if has_function_privilege(
        'authenticated',
        'public.beekeep_admin_manage_hive_limit_override(text,text,integer,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'Authenticated mobile users must not execute the admin RPC.';
    end if;
    if not has_function_privilege(
        'service_role',
        'public.beekeep_admin_manage_hive_limit_override(text,text,integer,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'The protected service-role Edge Function must be able to execute the admin RPC.';
    end if;
end;
$support_admin_assertions$;

rollback;
select 'Support-admin hive-limit tool tests passed' as result;
