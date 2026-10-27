-- Verify Pro and Ultimate custom limits, downgrade safety, admin audit history, and role isolation.
-- Run after 20261027130000_beekeep_pro_and_ultimate_hive_limit_overrides.sql.
begin;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000041', 'quota-admin@example.test',
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000042', 'quota-pro@example.test',
        '{"beekeep_plan":"pro_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000043', 'quota-ultimate@example.test',
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000044', 'quota-expired-pro@example.test',
        '{"beekeep_plan":"pro_yearly","beekeep_subscription_expires_at":"2000-01-01T00:00:00Z"}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000045', 'quota-free@example.test',
        '{}'::jsonb, '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000046', 'quota-spoofed-pro@example.test',
        '{}'::jsonb,
        '{"beekeep_plan":"pro_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

-- An expired paid account may retain an old override row, but the resolver must ignore it.
insert into public.beekeep_user_hive_limit_overrides (user_id, hive_limit_override, reason)
values (
    '00000000-0000-0000-0000-000000000044', 2500, 'Regression: expired Pro override'
)
on conflict (user_id) do update
set hive_limit_override = excluded.hive_limit_override,
    reason = excluded.reason;

do $paid_tier_assertions$
declare
    row_value record;
    denied boolean := false;
    current_limit integer;
begin
    if public.beekeep_server_base_hive_limit('00000000-0000-0000-0000-000000000042') <> 1000 then
        raise exception 'Active Pro must resolve to its standard 1,000 hive allowance.';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000042') <> 1000 then
        raise exception 'Pro must start at the standard 1,000 hive allowance before an override.';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000044') <> 150 then
        raise exception 'Expired Pro must ignore a previously saved custom allowance.';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000046') <> 150 then
        raise exception 'Editable raw_user_meta_data must not grant Pro or custom capacity.';
    end if;

    select * into row_value
      from public.beekeep_admin_manage_hive_limit_override(
        'lookup', 'QUOTA-PRO@example.test', null, null,
        '00000000-0000-0000-0000-000000000041'
      );
    if row_value.is_active_paid_plan is distinct from true
       or row_value.is_active_ultimate is distinct from false
       or row_value.base_hive_limit <> 1000 then
        raise exception 'Admin lookup must recognize active Pro and report its 1,000 standard allowance.';
    end if;

    select * into row_value
      from public.beekeep_admin_manage_hive_limit_override(
        'set', 'quota-pro@example.test', 1500, 'Regression: approved Pro custom limit',
        '00000000-0000-0000-0000-000000000041'
      );
    if row_value.custom_hive_limit <> 1500
       or row_value.base_hive_limit <> 1000
       or row_value.is_active_paid_plan is distinct from true then
        raise exception 'Active Pro must accept a custom 1,500 hive limit.';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000042') <> 1500 then
        raise exception 'Cloud sync must resolve the Pro custom allowance to 1,500.';
    end if;
    if row_value.override_updated_by <> '00000000-0000-0000-0000-000000000041'::uuid then
        raise exception 'The saved override must retain its support-admin actor.';
    end if;

    begin
        perform 1 from public.beekeep_admin_manage_hive_limit_override(
            'set', 'quota-pro@example.test', 999, 'Below the Pro base allowance',
            '00000000-0000-0000-0000-000000000041'
        );
        raise exception 'A Pro override must not reduce the 1,000 standard allowance.';
    exception when invalid_parameter_value then
        null;
    end;

    begin
        perform 1 from public.beekeep_admin_manage_hive_limit_override(
            'set', 'quota-free@example.test', 1500, 'A Free account must be rejected',
            '00000000-0000-0000-0000-000000000041'
        );
        raise exception 'Free accounts must not receive paid-plan custom hive allowances.';
    exception when invalid_parameter_value then
        null;
    end;

    select * into row_value
      from public.beekeep_admin_manage_hive_limit_override(
        'set', 'quota-ultimate@example.test', 11000, 'Regression: approved Ultimate custom limit',
        '00000000-0000-0000-0000-000000000041'
      );
    if row_value.custom_hive_limit <> 11000
       or row_value.base_hive_limit <> 10000
       or row_value.is_active_ultimate is distinct from true then
        raise exception 'Active Ultimate must continue to accept a custom 11,000 hive limit.';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000043') <> 11000 then
        raise exception 'Ultimate custom allowance must still be enforced by cloud sync.';
    end if;

    -- Signed-in customers can read only their own verified Pro allowance through the dedicated RPC.
    perform set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000042', true);
    select hive_limit_override into current_limit
      from public.beekeep_my_hive_limit_override();
    if current_limit <> 1500 then
        raise exception 'The account-level entitlement RPC must return a Pro custom allowance of 1,500.';
    end if;
    select * into row_value
      from public.beekeep_my_hive_entitlement();
    if row_value.plan_code <> 'pro_yearly'
       or row_value.base_hive_limit <> 1000
       or row_value.custom_hive_limit <> 1500
       or row_value.effective_hive_limit <> 1500
       or row_value.is_active_paid_plan is distinct from true
       or row_value.is_active_ultimate is distinct from false then
        raise exception 'The current-account entitlement RPC must expose the trusted Pro tier and resolved 1,500 hive ceiling.';
    end if;

    -- Admin functions and the private audit table remain unavailable to authenticated app users.
    if has_function_privilege(
        'authenticated',
        'public.beekeep_admin_manage_hive_limit_override(text,text,integer,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'Authenticated customers must not execute the support-admin RPC.';
    end if;
    if has_table_privilege('authenticated', 'public.beekeep_hive_limit_override_audit', 'SELECT') then
        raise exception 'Authenticated customers must not read the support audit history table directly.';
    end if;
    if has_function_privilege('anon', 'public.beekeep_my_hive_entitlement()', 'EXECUTE') then
        raise exception 'Anonymous users must not execute the current-account entitlement RPC.';
    end if;
    if not has_function_privilege(
        'service_role',
        'public.beekeep_admin_manage_hive_limit_override(text,text,integer,text,uuid)',
        'EXECUTE'
    ) then
        raise exception 'Only the protected server-side service role should be able to execute the admin RPC.';
    end if;

    select * into row_value
      from public.beekeep_admin_manage_hive_limit_override(
        'clear', 'quota-pro@example.test', null, 'Regression: removed Pro override',
        '00000000-0000-0000-0000-000000000041'
      );
    if row_value.custom_hive_limit is not null
       or row_value.base_hive_limit <> 1000 then
        raise exception 'Clearing Pro override must restore its standard quota.';
    end if;
    if jsonb_array_length(row_value.audit_history) < 2 then
        raise exception 'The account audit history must retain both set and clear events.';
    end if;
    if not exists (
        select 1 from public.beekeep_hive_limit_override_audit a
         where a.target_user_id = '00000000-0000-0000-0000-000000000042'::uuid
           and a.action = 'clear'
           and a.reason = 'Regression: removed Pro override'
    ) then
        raise exception 'Removing a custom limit must keep its actor, reason and history record.';
    end if;
end;
$paid_tier_assertions$;

rollback;
select 'Pro and Ultimate custom hive-limit tests passed' as result;
