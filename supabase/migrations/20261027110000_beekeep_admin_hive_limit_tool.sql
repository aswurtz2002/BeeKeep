-- Trusted support-admin operation for managing per-account Ultimate hive-limit exceptions.
-- Only the protected Edge Function's service-role client may invoke this function.

begin;

create or replace function public.beekeep_admin_manage_hive_limit_override(
    p_action text,
    p_email text,
    p_hive_limit integer default null,
    p_reason text default null,
    p_actor_id uuid default null
)
returns table (
    account_id uuid,
    account_email text,
    plan_code text,
    expires_at text,
    is_active_ultimate boolean,
    custom_hive_limit integer,
    override_reason text,
    override_updated_at timestamptz
)
language plpgsql
security definer
set search_path = ''
as $function$
declare
    v_action text;
    v_target_user_id uuid;
    v_target_email text;
    v_metadata jsonb;
    v_is_active_ultimate boolean;
begin
    v_action := pg_catalog.lower(pg_catalog.btrim(coalesce(p_action, '')));
    if v_action not in ('lookup', 'set', 'clear') then
        raise exception 'Unsupported support-admin action.'
            using errcode = '22023';
    end if;

    if p_email is null
       or pg_catalog.length(pg_catalog.btrim(p_email)) < 3
       or pg_catalog.length(pg_catalog.btrim(p_email)) > 254
       or pg_catalog.strpos(pg_catalog.btrim(p_email), '@') < 2 then
        raise exception 'Enter a valid account email.'
            using errcode = '22023';
    end if;

    -- Exact case-insensitive email matching; never return a customer list.
    select u.id, u.email, coalesce(u.raw_app_meta_data, '{}'::jsonb)
      into v_target_user_id, v_target_email, v_metadata
      from auth.users u
     where pg_catalog.lower(pg_catalog.btrim(u.email)) =
           pg_catalog.lower(pg_catalog.btrim(p_email))
     limit 1;

    if v_target_user_id is null then
        raise exception 'No BeeKeep account was found with that exact email.'
            using errcode = 'P0002';
    end if;

    v_is_active_ultimate := public.beekeep_user_has_active_ultimate(v_target_user_id);

    if v_action = 'set' then
        if v_is_active_ultimate is distinct from true then
            raise exception 'A custom hive limit can only be assigned to an active Ultimate account.'
                using errcode = '22023';
        end if;
        if p_hive_limit is null or p_hive_limit < 10000 or p_hive_limit > 1000000 then
            raise exception 'Custom hive limits must be between 10,000 and 1,000,000.'
                using errcode = '22023';
        end if;
        if p_reason is null
           or pg_catalog.length(pg_catalog.btrim(p_reason)) < 3
           or pg_catalog.length(pg_catalog.btrim(p_reason)) > 500 then
            raise exception 'Enter a reason between 3 and 500 characters.'
                using errcode = '22023';
        end if;
        if p_actor_id is null then
            raise exception 'The support-admin actor is required for the audit trail.'
                using errcode = '22023';
        end if;

        insert into public.beekeep_user_hive_limit_overrides
            (user_id, hive_limit_override, reason, updated_by)
        values
            (v_target_user_id, p_hive_limit, pg_catalog.btrim(p_reason), p_actor_id)
        on conflict (user_id) do update
            set hive_limit_override = excluded.hive_limit_override,
                reason = excluded.reason,
                updated_by = excluded.updated_by,
                updated_at = pg_catalog.now();
    elsif v_action = 'clear' then
        delete from public.beekeep_user_hive_limit_overrides o
         where o.user_id = v_target_user_id;
    end if;

    return query
    select
        v_target_user_id,
        v_target_email,
        coalesce(v_metadata ->> 'beekeep_plan', 'free'),
        nullif(v_metadata ->> 'beekeep_subscription_expires_at', ''),
        v_is_active_ultimate,
        o.hive_limit_override,
        o.reason,
        o.updated_at
      from (select 1) as singleton
      left join public.beekeep_user_hive_limit_overrides o
        on o.user_id = v_target_user_id;
end;
$function$;

revoke all on function public.beekeep_admin_manage_hive_limit_override(text, text, integer, text, uuid)
    from public, anon, authenticated;
grant execute on function public.beekeep_admin_manage_hive_limit_override(text, text, integer, text, uuid)
    to service_role;

commit;
