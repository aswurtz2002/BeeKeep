-- Prevent duplicate usernames from causing Supabase Auth sign-up to fail with HTTP 500.
-- The app checks availability before registration; this trigger also handles races safely.

begin;

create or replace function public.beekeep_is_username_available(target_username text)
returns table (available boolean)
language sql
stable
security definer
set search_path = public, pg_temp
as $function$
    select case
        when trim(coalesce(target_username, '')) ~ '^[A-Za-z0-9_]{3,20}$'
        then not exists (
            select 1
            from public.beekeep_profiles p
            where lower(p.username) = lower(trim(target_username))
        )
        else false
    end as available;
$function$;

revoke all on function public.beekeep_is_username_available(text) from public;
grant execute on function public.beekeep_is_username_available(text) to anon, authenticated;

create or replace function public.beekeep_sync_profile_username_from_auth()
returns trigger
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    requested_username text := trim(coalesce(new.raw_user_meta_data ->> 'username', ''));
begin
    if requested_username ~ '^[A-Za-z0-9_]{3,20}$' then
        begin
            insert into public.beekeep_profiles (user_id, username)
            values (new.id, requested_username)
            on conflict (user_id) do update
            set username = excluded.username,
                updated_at = now();
        exception
            when unique_violation then
                -- A different account claimed this case-insensitive username between the app's
                -- availability check and registration. Keep account creation working and remove
                -- the unclaimed handle so workspace roster lookups cannot report it as the user's.
                update auth.users
                set raw_user_meta_data = coalesce(raw_user_meta_data, '{}'::jsonb) - 'username'
                where id = new.id;
        end;
    end if;
    return new;
end;
$function$;

revoke all on function public.beekeep_sync_profile_username_from_auth() from public, anon, authenticated;

commit;
