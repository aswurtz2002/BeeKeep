-- Regression coverage for trusted per-account Ultimate hive limits.
-- Run after 20261027062000_beekeep_per_account_hive_limit_overrides.sql on a disposable
-- Supabase/Postgres test database. Everything is rolled back at the end.

begin;

insert into auth.users (id, email, raw_app_meta_data, raw_user_meta_data)
values
    ('00000000-0000-0000-0000-000000000021', 'hive-limit-ultimate@example.test',
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb,
        '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000022', 'hive-limit-expired@example.test',
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2000-01-01T00:00:00Z"}'::jsonb,
        '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000023', 'hive-limit-pro@example.test',
        '{"beekeep_plan":"pro_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb,
        '{}'::jsonb),
    ('00000000-0000-0000-0000-000000000024', 'hive-limit-spoof@example.test',
        '{}'::jsonb,
        '{"beekeep_plan":"ultimate_yearly","beekeep_subscription_expires_at":"2099-01-01T00:00:00Z"}'::jsonb)
on conflict (id) do update
set email = excluded.email,
    raw_app_meta_data = excluded.raw_app_meta_data,
    raw_user_meta_data = excluded.raw_user_meta_data;

-- Admin-created values for expired/non-Ultimate accounts must not grant extra capacity.
insert into public.beekeep_user_hive_limit_overrides (user_id, hive_limit_override, reason)
values
    ('00000000-0000-0000-0000-000000000021', 11000, 'Regression test: custom Ultimate allowance'),
    ('00000000-0000-0000-0000-000000000022', 12000, 'Regression test: expired entitlement must ignore override'),
    ('00000000-0000-0000-0000-000000000023', 12000, 'Regression test: Pro entitlement must ignore override'),
    ('00000000-0000-0000-0000-000000000024', 12000, 'Regression test: editable metadata must not grant Ultimate')
on conflict (user_id) do update
set hive_limit_override = excluded.hive_limit_override,
    reason = excluded.reason;

do $limit_resolution_assertions$
begin
    if public.beekeep_server_base_hive_limit('00000000-0000-0000-0000-000000000021') <> 10000 then
        raise exception 'Active Ultimate should have the standard 10,000 hive limit';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000021') <> 11000 then
        raise exception 'Active Ultimate custom override should resolve to 11,000';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000022') <> 150 then
        raise exception 'Expired Ultimate must resolve to the Free limit even when an override row exists';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000023') <> 1000 then
        raise exception 'Pro must resolve to 1,000 and ignore an Ultimate-only override';
    end if;
    if public.beekeep_resolved_hive_limit('00000000-0000-0000-0000-000000000024') <> 150 then
        raise exception 'User-editable raw_user_meta_data must never grant Ultimate access';
    end if;
end;
$limit_resolution_assertions$;

-- The RPC reveals only the current authenticated account's override and accepts no target ID.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000021', true);
do $rpc_assertions$
declare actual_limit integer;
begin
    select hive_limit_override
      into actual_limit
      from public.beekeep_my_hive_limit_override();
    if actual_limit <> 11000 then
        raise exception 'Current active Ultimate user should receive override 11,000; got %', actual_limit;
    end if;

    begin
        perform 1 from public.beekeep_user_hive_limit_overrides;
        raise exception 'Authenticated users must not read the internal override table';
    exception when insufficient_privilege then
        null;
    end;
end;
$rpc_assertions$;

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000022', true);
do $expired_rpc_assertion$
declare actual_limit integer;
begin
    select hive_limit_override
      into actual_limit
      from public.beekeep_my_hive_limit_override();
    if actual_limit is not null then
        raise exception 'Expired Ultimate must not receive a custom override';
    end if;
end;
$expired_rpc_assertion$;
reset role;

-- Exercise the insert guard using the Free tier's 150-record cap without creating thousands
-- of fixtures. Run as the matching authenticated account because the existing cloud trigger also
-- verifies auth.uid() against user_id. The fake override row must not increase the allowance.
set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000024', true);
insert into public.beekeep_documents (
    user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
)
select
    '00000000-0000-0000-0000-000000000024'::uuid,
    'hive',
    n,
    jsonb_build_object('id', n, 'number', n::text)::text,
    n,
    false,
    'hive-limit-regression'
from generate_series(1, 150) as n;

do $personal_limit_guard_assertion$
declare denied boolean := false;
begin
    begin
        insert into public.beekeep_documents (
            user_id, entity_type, entity_id, payload, updated_at, deleted, device_id
        ) values (
            '00000000-0000-0000-0000-000000000024',
            'hive', 151, '{"id":151,"number":"151"}', 151, false, 'hive-limit-regression'
        );
    exception when check_violation then
        denied := true;
    end;
    if not denied then
        raise exception 'Cloud sync should reject the 151st non-deleted Free hive';
    end if;
end;
$personal_limit_guard_assertion$;
reset role;

rollback;

select 'Per-account hive limit override tests passed' as result;
