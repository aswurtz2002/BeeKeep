-- Short, human-friendly workspace invite codes with per-account guess throttling.
-- Existing long invitations remain valid until they expire or are used.

begin;

-- Track failed code guesses. Ten misses in a fifteen-minute window temporarily
-- block further attempts by that signed-in account. Valid invite tokens remain email-bound.
create table if not exists public.beekeep_workspace_invitation_attempts (
    user_id uuid primary key references auth.users(id) on delete cascade,
    attempts integer not null default 0 check (attempts >= 0),
    window_started_at timestamptz not null default now(),
    blocked_until timestamptz
);
alter table public.beekeep_workspace_invitation_attempts enable row level security;
revoke all on table public.beekeep_workspace_invitation_attempts from public, anon, authenticated;

create or replace function public.beekeep_register_invalid_workspace_invitation_attempt()
returns boolean
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    attempt_row public.beekeep_workspace_invitation_attempts%rowtype;
    next_attempts integer;
    next_window timestamptz;
begin
    if caller_id is null then return false; end if;

    insert into public.beekeep_workspace_invitation_attempts(user_id)
    values (caller_id)
    on conflict (user_id) do nothing;

    select * into attempt_row
    from public.beekeep_workspace_invitation_attempts a
    where a.user_id = caller_id
    for update;

    if attempt_row.blocked_until is not null and attempt_row.blocked_until > now() then
        return false;
    end if;

    if attempt_row.window_started_at <= now() - interval '15 minutes' then
        next_attempts := 1;
        next_window := now();
    else
        next_attempts := attempt_row.attempts + 1;
        next_window := attempt_row.window_started_at;
    end if;

    update public.beekeep_workspace_invitation_attempts
    set attempts = next_attempts,
        window_started_at = next_window,
        blocked_until = case when next_attempts >= 10 then now() + interval '15 minutes' else null end
    where user_id = caller_id;

    return true;
end;
$function$;

revoke all on function public.beekeep_register_invalid_workspace_invitation_attempt() from public, anon, authenticated;

create or replace function public.beekeep_create_workspace_invitation(
    target_workspace_id uuid,
    invite_email text,
    target_role text default 'member'
)
returns table (
    invitation_id uuid,
    workspace_id uuid,
    email text,
    role text,
    invite_token text,
    expires_at timestamptz
)
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    caller_role text;
    cleaned_email text := lower(trim(coalesce(invite_email, '')));
    cleaned_role text := lower(trim(coalesce(target_role, 'member')));
    token_value text;
    attempt_number integer;
    char_number integer;
    token_ready boolean := false;
    invitation_expiry timestamptz := now() + interval '7 days';
begin
    if caller_id is null then raise exception 'Sign in to invite a beekeeper'; end if;
    if cleaned_email = '' or position('@' in cleaned_email) < 2 or length(cleaned_email) > 254 then
        raise exception 'Enter a valid email address';
    end if;
    if cleaned_role not in ('admin', 'member', 'viewer') then
        raise exception 'Choose Admin, Member or Viewer';
    end if;

    select m.role into caller_role
    from public.beekeep_workspace_members m
    join public.beekeep_workspaces w on w.id = m.workspace_id
    where m.workspace_id = target_workspace_id
      and m.user_id = caller_id
      and w.is_personal = false;

    if caller_role is null then
        raise exception 'Only members of a shared workspace can invite others';
    end if;
    if caller_role is null or caller_role not in ('owner', 'admin') then
        raise exception 'Only workspace owners and admins can invite others';
    end if;
    if caller_role = 'admin' and cleaned_role = 'admin' then
        raise exception 'Only the workspace owner can invite another admin';
    end if;

    if exists (
        select 1
        from auth.users u
        join public.beekeep_workspace_members m on m.user_id = u.id
        where m.workspace_id = target_workspace_id and lower(u.email) = cleaned_email
    ) then
        raise exception 'That email address is already a member of this workspace';
    end if;

    update public.beekeep_workspace_invitations i
    set status = 'expired'
    where i.workspace_id = target_workspace_id and i.status = 'pending' and i.expires_at <= now();

    if exists (
        select 1 from public.beekeep_workspace_invitations i
        where i.workspace_id = target_workspace_id and lower(i.email) = cleaned_email and i.status = 'pending'
    ) then
        raise exception 'There is already a pending invitation for that email';
    end if;

    -- Eight-character, case-insensitive base32 code. Excluding I, O, 0 and 1 avoids
    -- characters that are commonly misread when a code is shared verbally.
    for attempt_number in 1..10 loop
        token_value := '';
        for char_number in 1..8 loop
            token_value := token_value || substr(
                'ABCDEFGHJKLMNPQRSTUVWXYZ23456789',
                (get_byte(gen_random_bytes(1), 0) % 32) + 1,
                1
            );
        end loop;
        if not exists (
            select 1
            from public.beekeep_workspace_invitations i
            where i.token_hash = encode(digest(token_value, 'sha256'), 'hex')
        ) then
            token_ready := true;
            exit;
        end if;
    end loop;
    if not token_ready then raise exception 'Could not generate an invitation code; please try again'; end if;

    insert into public.beekeep_workspace_invitations (
        workspace_id, email, role, token_hash, status, created_by, expires_at
    ) values (
        target_workspace_id, cleaned_email, cleaned_role,
        encode(digest(token_value, 'sha256'), 'hex'), 'pending', caller_id, invitation_expiry
    );

    return query
    select i.id, i.workspace_id, i.email, i.role, token_value, i.expires_at
    from public.beekeep_workspace_invitations i
    where i.workspace_id = target_workspace_id
      and i.email = cleaned_email
      and i.token_hash = encode(digest(token_value, 'sha256'), 'hex')
      and i.status = 'pending';
end;
$function$;

create or replace function public.beekeep_accept_workspace_invitation(invite_token text)
returns table (
    workspace_id uuid,
    workspace_name text,
    workspace_role text
)
language plpgsql
security definer
set search_path = public, extensions, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    caller_email text;
    invite_row public.beekeep_workspace_invitations%rowtype;
    attempt_row public.beekeep_workspace_invitation_attempts%rowtype;
    token_value text := trim(coalesce(invite_token, ''));
begin
    if caller_id is null then raise exception 'Sign in before accepting an invitation'; end if;
    insert into public.beekeep_workspace_invitation_attempts(user_id)
    values (caller_id)
    on conflict (user_id) do nothing;

    select * into attempt_row
    from public.beekeep_workspace_invitation_attempts a
    where a.user_id = caller_id
    for update;

    if attempt_row.blocked_until is not null and attempt_row.blocked_until > now() then
        return;
    end if;

    if not (length(token_value) = 8 or length(token_value) between 32 and 128) then
        perform public.beekeep_register_invalid_workspace_invitation_attempt();
        return;
    end if;

    select lower(u.email) into caller_email from auth.users u where u.id = caller_id;
    if caller_email is null then raise exception 'Could not verify your account email'; end if;

    select i.* into invite_row
    from public.beekeep_workspace_invitations i
    where i.token_hash in (
        encode(digest(token_value, 'sha256'), 'hex'),
        encode(digest(lower(token_value), 'sha256'), 'hex'),
        encode(digest(upper(token_value), 'sha256'), 'hex')
    )
    for update;

    if invite_row.id is null then
        perform public.beekeep_register_invalid_workspace_invitation_attempt();
        return;
    end if;
    if invite_row.status <> 'pending' then raise exception 'This invitation is no longer active'; end if;
    if invite_row.expires_at <= now() then
        update public.beekeep_workspace_invitations set status = 'expired' where id = invite_row.id;
        raise exception 'This invitation has expired';
    end if;
    if lower(invite_row.email) <> caller_email then
        raise exception 'Sign in with the email address the invitation was sent to';
    end if;

    insert into public.beekeep_workspace_members (workspace_id, user_id, role, invited_by)
    values (invite_row.workspace_id, caller_id, invite_row.role, invite_row.created_by)
    on conflict on constraint beekeep_workspace_members_pkey do nothing;

    update public.beekeep_workspace_invitations
    set status = 'accepted', accepted_by = caller_id
    where id = invite_row.id and status = 'pending';

    return query
    select w.id, w.name, m.role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m on m.workspace_id = w.id and m.user_id = caller_id
    where w.id = invite_row.workspace_id;
end;
$function$;

revoke all on function public.beekeep_create_workspace_invitation(uuid, text, text) from public, anon;
revoke all on function public.beekeep_accept_workspace_invitation(text) from public, anon;
grant execute on function public.beekeep_create_workspace_invitation(uuid, text, text) to authenticated;
grant execute on function public.beekeep_accept_workspace_invitation(text) to authenticated;

commit;
