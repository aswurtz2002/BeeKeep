-- Enforce the one-trusted-installation rule on all BeeKeep authenticated cloud data.
-- The Android device gate is merged and the matching device-access Edge Function is deployed.
-- Policies are scoped to authenticated; service_role maintenance remains unaffected.
begin;

-- These restrictive policies AND the existing per-user / workspace role policies.
-- They do not replace or weaken the existing row ownership and membership rules.
do $policies$
begin
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_action_settings'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_action_settings'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_admin_commercial_grant_audit'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_admin_commercial_grant_audit'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_admin_commercial_grants'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_admin_commercial_grants'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_document_workspaces'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_document_workspaces'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_documents'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_documents'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_hive_limit_override_audit'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_hive_limit_override_audit'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_personal_sync_state'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_personal_sync_state'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_personal_sync_versions'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_personal_sync_versions'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_profiles'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_profiles'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_user_hive_limit_overrides'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_user_hive_limit_overrides'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_action_log'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_action_log'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_action_log_settings'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_action_log_settings'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_documents'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_documents'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_invitation_attempts'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_invitation_attempts'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_invitations'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_invitations'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_members'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_members'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_sync_state'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_sync_state'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspace_sync_versions'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspace_sync_versions'
    );
    execute format(
        'drop policy if exists beekeep_trusted_device_required on public.%I',
        'beekeep_workspaces'
    );
    execute format(
        'create policy beekeep_trusted_device_required on public.%I as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())',
        'beekeep_workspaces'
    );
end;
$policies$;

-- Secure inspection-photo reads and writes with the same active-session predicate.
do $storage$
begin
    if to_regclass('storage.objects') is null then
        raise exception 'Supabase Storage objects table is not available; cannot enable trusted-device enforcement.';
    end if;
    execute 'drop policy if exists beekeep_trusted_device_required on storage.objects';
    execute 'create policy beekeep_trusted_device_required on storage.objects as restrictive for all to authenticated using (public.beekeep_device_session_is_trusted()) with check (public.beekeep_device_session_is_trusted())';
end;
$storage$;

-- SECURITY DEFINER functions bypass table-owner RLS. Add a trusted-session guard to
-- all user-callable account/workspace RPCs and their direct membership/entitlement helpers.
CREATE OR REPLACE FUNCTION public.beekeep_accept_workspace_invitation(invite_token text)
 RETURNS TABLE(workspace_id uuid, workspace_name text, workspace_role text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'extensions', 'pg_temp'
AS $function$
declare
    caller_id uuid := auth.uid();
    caller_email text;
    invite_row public.beekeep_workspace_invitations%rowtype;
    attempt_row public.beekeep_workspace_invitation_attempts%rowtype;
    owner_id uuid;
    owner_metadata jsonb;
    owner_plan text;
    owner_expiry_text text;
    owner_expiry timestamptz;
    token_value text := trim(coalesce(invite_token, ''));
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then
        raise exception 'Sign in before accepting an invitation';
    end if;

    insert into public.beekeep_workspace_invitation_attempts(user_id)
    values (caller_id)
    on conflict (user_id) do nothing;

    select *
      into attempt_row
      from public.beekeep_workspace_invitation_attempts a
     where a.user_id = caller_id
     for update;

    if attempt_row.blocked_until is not null and attempt_row.blocked_until > now() then
        return;
    end if;

    -- Current format: Bee + exactly six decimal digits. Keep legacy formats working.
    if not (
        token_value ~* '^Bee[0-9]{6}$'
        or length(token_value) = 8
        or length(token_value) between 32 and 128
    ) then
        perform public.beekeep_register_invalid_workspace_invitation_attempt();
        return;
    end if;

    select lower(u.email)
      into caller_email
      from auth.users u
     where u.id = caller_id;

    if caller_email is null then
        raise exception 'Could not verify your account email';
    end if;

    select i.*
      into invite_row
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
    if invite_row.status <> 'pending' then
        raise exception 'This invitation is no longer active';
    end if;
    if invite_row.expires_at <= now() then
        update public.beekeep_workspace_invitations
           set status = 'expired'
         where id = invite_row.id;
        raise exception 'This invitation has expired';
    end if;
    if lower(invite_row.email) <> caller_email then
        raise exception 'Sign in with the email address the invitation was sent to';
    end if;

    -- Lock the workspace and owner auth row so an entitlement change cannot race this
    -- acceptance check within the same transaction.
    select w.owner_user_id
      into owner_id
      from public.beekeep_workspaces w
     where w.id = invite_row.workspace_id
       and w.is_personal = false
     for share;

    if owner_id is null then
        raise exception 'This team workspace is no longer available';
    end if;

    select coalesce(u.raw_app_meta_data, '{}'::jsonb)
      into owner_metadata
      from auth.users u
     where u.id = owner_id
     for share;

    if not found then
        raise exception 'The team workspace owner account is unavailable'
            using errcode = '42501';
    end if;

    owner_plan := regexp_replace(
        lower(coalesce(owner_metadata ->> 'beekeep_plan', '')),
        '[^a-z]',
        '',
        'g'
    );
    owner_expiry_text := nullif(trim(owner_metadata ->> 'beekeep_subscription_expires_at'), '');
    owner_expiry := null;
    if owner_plan in (
        'commercial', 'commercialmonthly', 'commercialyearly',
        'ultimate', 'ultimatemonthly', 'ultimateyearly'
    ) and owner_expiry_text is not null then
        begin
            owner_expiry := owner_expiry_text::timestamptz;
        exception when others then
            owner_expiry := null;
        end;
    end if;

    if owner_expiry is null or owner_expiry <= now() then
        raise exception 'The team workspace owner must have an active BeeKeep Commercial subscription before this invitation can be accepted.'
            using errcode = '42501';
    end if;

    insert into public.beekeep_workspace_members (workspace_id, user_id, role, invited_by)
    values (invite_row.workspace_id, caller_id, invite_row.role, invite_row.created_by)
    on conflict on constraint beekeep_workspace_members_pkey do nothing;

    update public.beekeep_workspace_invitations
       set status = 'accepted',
           accepted_by = caller_id
     where id = invite_row.id
       and status = 'pending';

    return query
    select w.id, w.name, m.role
      from public.beekeep_workspaces w
      join public.beekeep_workspace_members m
        on m.workspace_id = w.id
       and m.user_id = caller_id
     where w.id = invite_row.workspace_id;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_cancel_workspace_invitation(target_invitation_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    invite_row public.beekeep_workspace_invitations%rowtype;
    caller_role text;
begin
    perform public.beekeep_require_trusted_device();
    if auth.uid() is null then
        raise exception 'Sign in to manage invitations';
    end if;

    select i.*
      into invite_row
      from public.beekeep_workspace_invitations i
     where i.id = target_invitation_id
     for update;

    if invite_row.id is null then raise exception 'Invitation not found'; end if;

    select m.role
      into caller_role
      from public.beekeep_workspace_members m
     where m.workspace_id = invite_row.workspace_id
       and m.user_id = auth.uid();

    if caller_role is null or caller_role not in ('owner', 'admin') then
        raise exception 'Only owners and admins can cancel invitations';
    end if;
    if not public.beekeep_workspace_owner_has_active_commercial(invite_row.workspace_id) then
        raise exception 'The team workspace owner must have an active BeeKeep Commercial subscription before invitations can be managed.'
            using errcode = '42501';
    end if;
    if invite_row.status <> 'pending' then
        raise exception 'Invitation is no longer pending';
    end if;
    if caller_role = 'admin' and invite_row.role = 'admin' then
        raise exception 'Only the owner can cancel an admin invitation';
    end if;

    update public.beekeep_workspace_invitations
       set status = 'cancelled'
     where id = invite_row.id;
    return true;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_copy_personal_documents_to_workspace(target_workspace_id uuid)
 RETURNS TABLE(copied_count integer)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    caller_id uuid := auth.uid();
    inserted_count integer := 0;
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then
        raise exception 'Sign in before copying personal records to a team workspace.'
            using errcode = '42501';
    end if;

    if target_workspace_id is null
       or not exists (
            select 1
            from public.beekeep_workspaces w
            where w.id = target_workspace_id
              and w.is_personal = false
              and public.has_beekeep_workspace_role(
                    w.id,
                    array['owner', 'admin']::text[]
                  )
       ) then
        raise exception 'Only a workspace owner or admin can copy personal records into this team.'
            using errcode = '42501';
    end if;

    if not public.beekeep_workspace_owner_has_active_ultimate(target_workspace_id) then
        raise exception 'The team workspace owner must have an active BeeKeep Ultimate subscription to copy records.'
            using errcode = '42501';
    end if;

    if exists (
        select 1
        from public.beekeep_documents d
        join public.beekeep_workspace_documents shared
          on shared.workspace_id = target_workspace_id
         and shared.entity_type = d.entity_type
         and shared.entity_id = d.entity_id
        where d.user_id = caller_id
    ) then
        raise exception 'Some personal record IDs already exist in this team workspace. No personal records were copied.'
            using errcode = '23505';
    end if;

    insert into public.beekeep_workspace_documents (
        workspace_id, entity_type, entity_id, payload, updated_at, deleted, device_id, updated_by
    )
    select
        target_workspace_id, d.entity_type, d.entity_id, d.payload, d.updated_at, d.deleted, d.device_id, caller_id
    from public.beekeep_documents d
    where d.user_id = caller_id;

    get diagnostics inserted_count = row_count;
    return query select inserted_count;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_create_workspace(workspace_name text)
 RETURNS TABLE(id uuid, name text, owner_user_id uuid, is_personal boolean, member_role text, created_at timestamp with time zone, updated_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    caller_id uuid := auth.uid();
    cleaned_name text := trim(coalesce(workspace_name, ''));
    new_workspace_id uuid;
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then raise exception 'Sign in to create a workspace'; end if;
    if cleaned_name = '' then raise exception 'Enter a workspace name'; end if;
    if length(cleaned_name) > 80 then raise exception 'Workspace names must be 80 characters or fewer'; end if;

    insert into public.beekeep_workspaces (name, owner_user_id, is_personal)
    values (cleaned_name, caller_id, false)
    returning beekeep_workspaces.id into new_workspace_id;

    insert into public.beekeep_workspace_members (workspace_id, user_id, role)
    values (new_workspace_id, caller_id, 'owner');

    return query
    select w.id, w.name, w.owner_user_id, w.is_personal, m.role, w.created_at, w.updated_at
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m on m.workspace_id = w.id and m.user_id = caller_id
    where w.id = new_workspace_id;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_create_workspace_invitation(target_workspace_id uuid, invite_email text, target_role text DEFAULT 'member'::text)
 RETURNS TABLE(invitation_id uuid, workspace_id uuid, email text, role text, invite_token text, expires_at timestamp with time zone)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'extensions', 'pg_temp'
AS $function$
declare
    caller_id uuid := auth.uid();
    caller_role text;
    cleaned_email text := lower(trim(coalesce(invite_email, '')));
    cleaned_role text := lower(trim(coalesce(target_role, 'member')));
    token_value text;
    attempt_number integer;
    char_number integer;
    random_byte integer;
    token_ready boolean := false;
    invitation_expiry timestamptz := now() + interval '7 days';
begin
    perform public.beekeep_require_trusted_device();
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
    if caller_role not in ('owner', 'admin') then
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

    -- Use six cryptographically random decimal digits. Rejection sampling avoids
    -- modulo bias; leading zeroes are intentionally retained so every code is 9 chars.
    -- The stored token is case-normalized before hashing; the user sees the "Bee" prefix.
    for attempt_number in 1..10 loop
        token_value := 'Bee';
        for char_number in 1..6 loop
            loop
                random_byte := get_byte(gen_random_bytes(1), 0);
                exit when random_byte < 250;
            end loop;
            token_value := token_value || (random_byte % 10)::text;
        end loop;

        if not exists (
            select 1
            from public.beekeep_workspace_invitations i
            where i.token_hash = encode(digest(upper(token_value), 'sha256'), 'hex')
        ) then
            token_ready := true;
            exit;
        end if;
    end loop;
    if not token_ready then
        raise exception 'Could not generate an invitation code; please try again';
    end if;

    insert into public.beekeep_workspace_invitations (
        workspace_id, email, role, token_hash, status, created_by, expires_at
    ) values (
        target_workspace_id, cleaned_email, cleaned_role,
        encode(digest(upper(token_value), 'sha256'), 'hex'),
        'pending', caller_id, invitation_expiry
    );

    return query
    select i.id, i.workspace_id, i.email, i.role, token_value, i.expires_at
    from public.beekeep_workspace_invitations i
    where i.workspace_id = target_workspace_id
      and i.email = cleaned_email
      and i.token_hash = encode(digest(upper(token_value), 'sha256'), 'hex')
      and i.status = 'pending';
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_delete_workspace(target_workspace_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    caller_id uuid := auth.uid();
    target_is_personal boolean;
    caller_role text;
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then
        raise exception 'Sign in to delete a workspace';
    end if;

    select w.is_personal, m.role
      into target_is_personal, caller_role
      from public.beekeep_workspaces w
      join public.beekeep_workspace_members m
        on m.workspace_id = w.id
       and m.user_id = caller_id
     where w.id = target_workspace_id
     for update of w;

    if target_is_personal is null then
        raise exception 'Workspace not found or you do not have access';
    end if;
    if target_is_personal then
        raise exception 'Your personal workspace cannot be deleted here';
    end if;
    if caller_role <> 'owner' then
        raise exception 'Only the workspace owner can delete this team';
    end if;
    if not public.beekeep_workspace_owner_has_active_commercial(target_workspace_id) then
        raise exception 'An active BeeKeep Commercial subscription is required to delete a team workspace.'
            using errcode = '42501';
    end if;

    delete from public.beekeep_workspaces
     where id = target_workspace_id
       and is_personal = false;

    if not found then
        raise exception 'Workspace could not be deleted';
    end if;
    return true;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_get_username()
 RETURNS TABLE(username text)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return query
    select coalesce(
        p.username,
        case
            when u.raw_user_meta_data ->> 'username' ~ '^[A-Za-z0-9_]{3,20}$'
            then u.raw_user_meta_data ->> 'username'
            else null
        end,
        ''
    ) as username
    from auth.users u
    left join public.beekeep_profiles p on p.user_id = u.id
    where u.id = auth.uid();
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_list_workspace_invitations(target_workspace_id uuid)
 RETURNS TABLE(id uuid, workspace_id uuid, email text, role text, status text, created_at timestamp with time zone, expires_at timestamp with time zone, created_by uuid)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    if auth.uid() is null then raise exception 'Sign in to view invitations'; end if;
    if not public.has_beekeep_workspace_role(target_workspace_id, array['owner', 'admin']) then
        raise exception 'Only workspace owners and admins can view invitations';
    end if;

    return query
    select i.id, i.workspace_id, i.email, i.role, i.status, i.created_at, i.expires_at, i.created_by
    from public.beekeep_workspace_invitations i
    where i.workspace_id = target_workspace_id
      and i.status = 'pending'
      and i.expires_at > now()
    order by i.created_at desc;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_list_workspace_members(target_workspace_id uuid)
 RETURNS TABLE(workspace_id uuid, user_id uuid, email text, username text, role text, joined_at timestamp with time zone, is_current_user boolean)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    if auth.uid() is null then
        raise exception 'Sign in to view workspace members';
    end if;
    if not public.is_beekeep_workspace_member(target_workspace_id) then
        raise exception 'You are not a member of this workspace';
    end if;

    return query
    select
        m.workspace_id,
        m.user_id,
        coalesce(u.email, '')::text,
        coalesce(
            p.username,
            case
                when u.raw_user_meta_data ->> 'username' ~ '^[A-Za-z0-9_]{3,20}$'
                then u.raw_user_meta_data ->> 'username'
                else null
            end,
            ''
        )::text as username,
        m.role::text,
        m.joined_at,
        (m.user_id = auth.uid())
    from public.beekeep_workspace_members m
    left join auth.users u on u.id = m.user_id
    left join public.beekeep_profiles p on p.user_id = m.user_id
    where m.workspace_id = target_workspace_id
    order by case m.role when 'owner' then 0 when 'admin' then 1 when 'member' then 2 else 3 end, m.joined_at;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_list_workspaces()
 RETURNS TABLE(id uuid, name text, owner_user_id uuid, is_personal boolean, member_role text, created_at timestamp with time zone, updated_at timestamp with time zone)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return query
    select w.id, w.name, w.owner_user_id, w.is_personal, m.role, w.created_at, w.updated_at
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m on m.workspace_id = w.id
    where m.user_id = auth.uid()
    order by w.is_personal desc, w.created_at asc;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_my_active_ultimate_entitlement()
 RETURNS TABLE(active boolean)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return query
    select public.beekeep_user_has_active_commercial(auth.uid());
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_my_hive_entitlement()
 RETURNS TABLE(plan_code text, base_hive_limit integer, custom_hive_limit integer, effective_hive_limit integer, expires_at text, is_active_paid_plan boolean, is_active_ultimate boolean)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return query
    select
        case
            when base.base_hive_limit >= 10000 then 'commercial'
            else 'hobbyist'
        end::text as plan_code,
        base.base_hive_limit,
        case
            when base.base_hive_limit >= 10000 then o.hive_limit_override
            else null
        end::integer as custom_hive_limit,
        public.beekeep_resolved_hive_limit(u.id) as effective_hive_limit,
        case
            when grant_row.user_id is not null
             and (grant_row.expires_at is null or grant_row.expires_at > pg_catalog.now())
                then grant_row.expires_at::text
            when base.base_hive_limit >= 10000
                then nullif(u.raw_app_meta_data ->> 'beekeep_subscription_expires_at', '')
            else null
        end::text as expires_at,
        (base.base_hive_limit >= 10000) as is_active_paid_plan,
        public.beekeep_user_has_active_commercial(u.id) as is_active_ultimate
    from auth.users u
    cross join lateral (
        select public.beekeep_server_base_hive_limit(u.id) as base_hive_limit
    ) base
    left join public.beekeep_user_hive_limit_overrides o
        on o.user_id = u.id
    left join public.beekeep_admin_commercial_grants grant_row
        on grant_row.user_id = u.id
    where u.id = auth.uid();
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_my_hive_limit_override()
 RETURNS TABLE(hive_limit_override integer)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return query
    select case
        when auth.uid() is not null
         and public.beekeep_server_base_hive_limit(auth.uid()) >= 10000
        then (
            select o.hive_limit_override
              from public.beekeep_user_hive_limit_overrides o
             where o.user_id = auth.uid()
        )
        else null
    end::integer as hive_limit_override;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_remove_workspace_member(target_workspace_id uuid, target_user_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    caller_role text;
    target_role text;
begin
    perform public.beekeep_require_trusted_device();
    if auth.uid() is null then
        raise exception 'Sign in to manage members';
    end if;

    select m.role
      into caller_role
      from public.beekeep_workspace_members m
      join public.beekeep_workspaces w on w.id = m.workspace_id
     where m.workspace_id = target_workspace_id
       and m.user_id = auth.uid()
       and w.is_personal = false;

    if caller_role is null or caller_role not in ('owner', 'admin') then
        raise exception 'Only owners and admins can remove members';
    end if;
    if not public.beekeep_workspace_owner_has_active_commercial(target_workspace_id) then
        raise exception 'The team workspace owner must have an active BeeKeep Commercial subscription before team changes can be made.'
            using errcode = '42501';
    end if;

    select m.role
      into target_role
      from public.beekeep_workspace_members m
     where m.workspace_id = target_workspace_id
       and m.user_id = target_user_id;

    if target_role is null then raise exception 'Workspace member not found'; end if;
    if target_role = 'owner' then raise exception 'The workspace owner cannot be removed'; end if;
    if target_user_id = auth.uid() then
        raise exception 'Use workspace transfer or owner tools before leaving your workspace';
    end if;
    if caller_role = 'admin' and target_role = 'admin' then
        raise exception 'Only the owner can remove another admin';
    end if;

    delete from public.beekeep_workspace_members
     where workspace_id = target_workspace_id
       and user_id = target_user_id;
    return true;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_set_username(target_username text)
 RETURNS TABLE(username text)
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
declare
    caller_id uuid := auth.uid();
    cleaned_username text := trim(coalesce(target_username, ''));
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then
        raise exception 'Sign in to set your username';
    end if;
    if cleaned_username !~ '^[A-Za-z0-9_]{3,20}$' then
        raise exception 'Username must be 3-20 characters using only letters, numbers, or underscores';
    end if;
    if exists (
        select 1 from public.beekeep_profiles p
        where lower(p.username) = lower(cleaned_username)
          and p.user_id <> caller_id
    ) then
        raise exception 'That username is already taken';
    end if;

    insert into public.beekeep_profiles (user_id, username)
    values (caller_id, cleaned_username)
    on conflict (user_id) do update
    set username = excluded.username,
        updated_at = now();

    return query
    select p.username from public.beekeep_profiles p where p.user_id = caller_id;
exception
    when unique_violation then
        raise exception 'That username is already taken';
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_update_workspace_member_role(target_workspace_id uuid, target_user_id uuid, new_role text)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    caller_role text;
    target_role text;
begin
    perform public.beekeep_require_trusted_device();
    if auth.uid() is null then
        raise exception 'Sign in to manage members';
    end if;
    if new_role is null or new_role not in ('admin', 'member', 'viewer') then
        raise exception 'Choose Admin, Member or Viewer';
    end if;

    select m.role
      into caller_role
      from public.beekeep_workspace_members m
      join public.beekeep_workspaces w on w.id = m.workspace_id
     where m.workspace_id = target_workspace_id
       and m.user_id = auth.uid()
       and w.is_personal = false;

    if caller_role is null or caller_role not in ('owner', 'admin') then
        raise exception 'Only owners and admins can change member roles';
    end if;
    if not public.beekeep_workspace_owner_has_active_commercial(target_workspace_id) then
        raise exception 'The team workspace owner must have an active BeeKeep Commercial subscription before team changes can be made.'
            using errcode = '42501';
    end if;

    select m.role
      into target_role
      from public.beekeep_workspace_members m
     where m.workspace_id = target_workspace_id
       and m.user_id = target_user_id;

    if target_role is null then raise exception 'Workspace member not found'; end if;
    if target_role = 'owner' then raise exception 'Workspace ownership cannot be changed here'; end if;
    if caller_role = 'admin' and (target_role = 'admin' or new_role = 'admin') then
        raise exception 'Only the owner can manage admin roles';
    end if;
    if target_user_id = auth.uid() then raise exception 'You cannot change your own role'; end if;

    update public.beekeep_workspace_members
       set role = new_role
     where workspace_id = target_workspace_id
       and user_id = target_user_id;
    return true;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_workspace_action_log_delete(target_entry_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    caller_id uuid := (select auth.uid());
    target_workspace_id uuid;
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then
        raise exception 'Sign in before deleting a workspace action entry.'
            using errcode = '42501';
    end if;

    select l.workspace_id
      into target_workspace_id
      from public.beekeep_workspace_action_log l
     where l.id = target_entry_id;

    if not found then
        return false;
    end if;

    perform 1
    from public.beekeep_workspaces w
    where w.id = target_workspace_id
      and w.is_personal = false
      and w.owner_user_id = caller_id
      and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    for update;

    if not found then
        raise exception 'Only the workspace owner can delete action-log entries.'
            using errcode = '42501';
    end if;

    update public.beekeep_workspace_action_log
       set deleted_at = pg_catalog.clock_timestamp()
     where id = target_entry_id
       and workspace_id = target_workspace_id
       and deleted_at is null;

    return found;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_workspace_action_log_delete_all(target_workspace_id uuid)
 RETURNS integer
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    caller_id uuid := (select auth.uid());
    affected_count integer := 0;
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then
        raise exception 'Sign in before deleting workspace action entries.'
            using errcode = '42501';
    end if;

    perform 1
    from public.beekeep_workspaces w
    where w.id = target_workspace_id
      and w.is_personal = false
      and w.owner_user_id = caller_id
      and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    for update;

    if not found then
        raise exception 'Only the workspace owner can delete action-log entries.'
            using errcode = '42501';
    end if;

    update public.beekeep_workspace_action_log
       set deleted_at = pg_catalog.clock_timestamp()
     where workspace_id = target_workspace_id
       and deleted_at is null;

    get diagnostics affected_count = row_count;
    return affected_count;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_workspace_action_log_set_retention(target_workspace_id uuid, target_retention_mode text)
 RETURNS text
 LANGUAGE plpgsql
 SECURITY DEFINER
 SET search_path TO ''
AS $function$
declare
    caller_id uuid := (select auth.uid());
    cleaned_mode text := pg_catalog.lower(pg_catalog.btrim(coalesce(target_retention_mode, '')));
begin
    perform public.beekeep_require_trusted_device();
    if caller_id is null then
        raise exception 'Sign in before changing workspace action retention.'
            using errcode = '42501';
    end if;

    if cleaned_mode not in ('keep', 'expire_90_days') then
        raise exception 'Choose Keep Workspace Entries or expire after 90 days.'
            using errcode = '22023';
    end if;

    perform 1
    from public.beekeep_workspaces w
    where w.id = target_workspace_id
      and w.is_personal = false
      and w.owner_user_id = caller_id
      and public.has_beekeep_workspace_role(w.id, array['owner']::text[])
    for update;

    if not found then
        raise exception 'Only the owner of this team workspace can change action retention.'
            using errcode = '42501';
    end if;

    insert into public.beekeep_workspace_action_log_settings (
        workspace_id, retention_mode, updated_at, updated_by
    )
    values (
        target_workspace_id, cleaned_mode, pg_catalog.clock_timestamp(), caller_id
    )
    on conflict (workspace_id) do update
       set retention_mode = excluded.retention_mode,
           updated_at = excluded.updated_at,
           updated_by = excluded.updated_by;

    return cleaned_mode;
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_workspace_owner_commercial_entitlement(target_workspace_id uuid)
 RETURNS TABLE(active boolean)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return query
    select public.beekeep_workspace_owner_has_active_commercial(target_workspace_id);
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_workspace_owner_has_active_ultimate(target_workspace_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return public.beekeep_workspace_owner_has_active_commercial(target_workspace_id);
end;
$function$;

CREATE OR REPLACE FUNCTION public.beekeep_workspace_owner_quota_entitlement(target_workspace_id uuid)
 RETURNS TABLE(active boolean, effective_hive_limit integer, effective_apiary_limit integer, member_role text)
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO ''
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return query
    select
        public.beekeep_user_has_active_commercial(w.owner_user_id) as active,
        public.beekeep_resolved_hive_limit(w.owner_user_id) as effective_hive_limit,
        public.beekeep_resolved_apiary_limit(w.owner_user_id) as effective_apiary_limit,
        m.role as member_role
    from public.beekeep_workspaces w
    join public.beekeep_workspace_members m
      on m.workspace_id = w.id
     and m.user_id = auth.uid()
    where w.id = target_workspace_id
      and w.is_personal = false;
end;
$function$;

CREATE OR REPLACE FUNCTION public.has_beekeep_workspace_role(target_workspace_id uuid, allowed_roles text[])
 RETURNS boolean
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return exists (
        select 1
        from public.beekeep_workspace_members m
        where m.workspace_id = target_workspace_id
          and m.user_id = auth.uid()
          and m.role = any(allowed_roles)
    );
end;
$function$;

CREATE OR REPLACE FUNCTION public.is_beekeep_workspace_member(target_workspace_id uuid)
 RETURNS boolean
 LANGUAGE plpgsql
 STABLE SECURITY DEFINER
 SET search_path TO 'public', 'pg_temp'
AS $function$
begin
    perform public.beekeep_require_trusted_device();
    return exists (
        select 1
        from public.beekeep_workspace_members m
        where m.workspace_id = target_workspace_id
          and m.user_id = auth.uid()
    );
end;
$function$;

-- Username availability intentionally remains callable by anon during account creation.
-- It only returns a boolean and exposes no account records.

commit;
