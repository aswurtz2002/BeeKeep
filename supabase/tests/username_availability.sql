-- Username availability endpoint and duplicate-sign-up race regression tests.
-- Runs after the workspace username migration on a disposable PostgreSQL database.

do $availability_assertions$
declare available_value boolean;
begin
    select available into available_value
    from public.beekeep_is_username_available('BeeBoss_1');

    if coalesce(available_value, true) then
        raise exception 'Existing username was reported as available';
    end if;

    select available into available_value
    from public.beekeep_is_username_available('beeboss_1');

    if coalesce(available_value, true) then
        raise exception 'Case-insensitive username conflict was not detected';
    end if;

    select available into available_value
    from public.beekeep_is_username_available('UniqueBee_987');

    if not coalesce(available_value, false) then
        raise exception 'An unused valid username was reported as unavailable';
    end if;

    select available into available_value
    from public.beekeep_is_username_available('not a valid username');

    if coalesce(available_value, true) then
        raise exception 'An invalid username was reported as available';
    end if;
end;
$availability_assertions$;

-- A race after availability check must not abort auth.users insertion with a 500.
insert into auth.users (id, email, raw_user_meta_data)
values (
    '00000000-0000-0000-0000-000000000006',
    'duplicate-username-trigger@example.test',
    '{"username":"beeboss_1"}'::jsonb
);

do $duplicate_signup_race_assertions$
begin
    if not exists (
        select 1 from auth.users
        where id = '00000000-0000-0000-0000-000000000006'
    ) then
        raise exception 'Sign-up with a concurrently claimed username did not create the account';
    end if;

    if exists (
        select 1 from public.beekeep_profiles
        where user_id = '00000000-0000-0000-0000-000000000006'
    ) then
        raise exception 'A duplicate username profile was unexpectedly created';
    end if;

    if exists (
        select 1 from auth.users
        where id = '00000000-0000-0000-0000-000000000006'
          and raw_user_meta_data ? 'username'
    ) then
        raise exception 'The unclaimed duplicate username remained in auth metadata';
    end if;
end;
$duplicate_signup_race_assertions$;

select 'Username availability and duplicate sign-up race tests passed' as result;
