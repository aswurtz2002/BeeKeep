-- Step 3: enforce Commercial entitlements, invitation acceptance, and apiary limits
-- at the Supabase/Postgres boundary.
--
-- Trusted plan metadata is read only from auth.users.raw_app_meta_data. Legacy Ultimate
-- values remain a Commercial compatibility alias; legacy Pro values resolve to Hobbyist.
-- Existing rows are retained. When an account is over a downgraded cap, edits to existing
-- records remain possible, but net-new active records are rejected.
begin;

create or replace function public.beekeep_user_has_active_commercial(target_user_id uuid)
returns boolean
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    metadata jsonb;
    normalized_plan text;
    expiry_text text;
    expiry_value timestamptz;
begin
    if target_user_id is null then
        return false;
    end if;

    select coalesce(u.raw_app_meta_data, '{}'::jsonb)
      into metadata
      from auth.users u
     where u.id = target_user_id;

    if not found then
        return false;
    end if;

    normalized_plan := regexp_replace(
        lower(coalesce(metadata ->> 'beekeep_plan', '')),
        '[^a-z]',
        '',
        'g'
    );

    -- Commercial is the canonical plan. Ultimate values are retained for existing
    -- subscriptions until billing metadata is migrated. Pro is deliberately not eligible.
    if normalized_plan not in (
        'commercial', 'commercialmonthly', 'commercialyearly',
        'ultimate', 'ultimatemonthly', 'ultimateyearly'
    ) then
        return false;
    end if;

    expiry_text := nullif(trim(metadata ->> 'beekeep_subscription_expires_at'), '');
    if expiry_text is null then
        return false;
    end if;

    begin
        expiry_value := expiry_text::timestamptz;
    exception when others then
        return false;
    end;

    return expiry_value > now();
end;
$function$;

revoke all on function public.beekeep_user_has_active_commercial(uuid)
    from public, anon, authenticated;

-- Keep the historical helper callable only through trusted database functions. Its
-- behavior now represents the canonical Commercial entitlement.
create or replace function public.beekeep_user_has_active_ultimate(target_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select public.beekeep_user_has_active_commercial(target_user_id);
$function$;

revoke all on function public.beekeep_user_has_active_ultimate(uuid)
    from public, anon, authenticated;

create or replace function public.beekeep_workspace_owner_has_active_commercial(target_workspace_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select coalesce((
        select public.beekeep_user_has_active_commercial(w.owner_user_id)
          from public.beekeep_workspaces w
         where w.id = target_workspace_id
           and w.is_personal = false
           and exists (
               select 1
                 from public.beekeep_workspace_members m
                where m.workspace_id = w.id
                  and m.user_id = auth.uid()
           )
    ), false);
$function$;

revoke all on function public.beekeep_workspace_owner_has_active_commercial(uuid)
    from public, anon;
grant execute on function public.beekeep_workspace_owner_has_active_commercial(uuid)
    to authenticated;

-- Backward-compatible RPC/policy helper used by the currently shipped Android client.
create or replace function public.beekeep_workspace_owner_has_active_ultimate(target_workspace_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $function$
    select public.beekeep_workspace_owner_has_active_commercial(target_workspace_id);
$function$;

revoke all on function public.beekeep_workspace_owner_has_active_ultimate(uuid)
    from public, anon;
grant execute on function public.beekeep_workspace_owner_has_active_ultimate(uuid)
    to authenticated;

create or replace function public.beekeep_my_active_ultimate_entitlement()
returns table(active boolean)
language sql
stable
security definer
set search_path = ''
as $function$
    select public.beekeep_user_has_active_commercial(auth.uid());
$function$;

revoke all on function public.beekeep_my_active_ultimate_entitlement()
    from public, anon;
grant execute on function public.beekeep_my_active_ultimate_entitlement()
    to authenticated;

-- Canonical subscription limits: Hobbyist 15 hives / 3 apiaries; Commercial
-- 10,000 hives / 1,000 apiaries. Existing per-account hive overrides are Commercial-only.
create or replace function public.beekeep_server_base_hive_limit(target_user_id uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $function$
    select case
        when public.beekeep_user_has_active_commercial(target_user_id) then 10000
        else 15
    end;
$function$;

revoke all on function public.beekeep_server_base_hive_limit(uuid)
    from public, anon, authenticated;

create or replace function public.beekeep_resolved_hive_limit(target_user_id uuid)
returns integer
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
    base_limit integer;
    custom_limit integer;
begin
    base_limit := public.beekeep_server_base_hive_limit(target_user_id);
    if base_limit < 10000 then
        return base_limit;
    end if;

    select o.hive_limit_override
      into custom_limit
      from public.beekeep_user_hive_limit_overrides o
     where o.user_id = target_user_id;

    return greatest(base_limit, coalesce(custom_limit, base_limit));
end;
$function$;

revoke all on function public.beekeep_resolved_hive_limit(uuid)
    from public, anon, authenticated;

create or replace function public.beekeep_my_hive_limit_override()
returns table(hive_limit_override integer)
language sql
stable
security definer
set search_path = ''
as $function$
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
$function$;

revoke all on function public.beekeep_my_hive_limit_override()
    from public, anon;
grant execute on function public.beekeep_my_hive_limit_override()
    to authenticated;

create or replace function public.beekeep_my_hive_entitlement()
returns table (
    plan_code text,
    base_hive_limit integer,
    custom_hive_limit integer,
    effective_hive_limit integer,
    expires_at text,
    is_active_paid_plan boolean,
    is_active_ultimate boolean
)
language sql
stable
security definer
set search_path = ''
as $function$
    select
        case
            when base.base_hive_limit >= 10000
                then coalesce(nullif(u.raw_app_meta_data ->> 'beekeep_plan', ''), 'commercial')
            else 'hobbyist'
        end::text as plan_code,
        base.base_hive_limit,
        case
            when base.base_hive_limit >= 10000 then o.hive_limit_override
            else null
        end::integer as custom_hive_limit,
        public.beekeep_resolved_hive_limit(u.id) as effective_hive_limit,
        case
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
     where u.id = auth.uid();
$function$;

revoke all on function public.beekeep_my_hive_entitlement()
    from public, anon;
grant execute on function public.beekeep_my_hive_entitlement()
    to authenticated;

create or replace function public.beekeep_resolved_apiary_limit(target_user_id uuid)
returns integer
language sql
stable
security definer
set search_path = ''
as $function$
    select case
        when public.beekeep_user_has_active_commercial(target_user_id) then 1000
        else 3
    end;
$function$;

revoke all on function public.beekeep_resolved_apiary_limit(uuid)
    from public, anon, authenticated;

-- APIARY quota guards mirror the established hive quota design and count only active rows.
-- Non-increasing edits and tombstones remain allowed after downgrade; only new active apiaries
-- beyond the account/workspace owner's current limit are rejected.
create or replace function public.beekeep_enforce_personal_apiary_limit_after_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    account_row record;
    account_limit integer;
    existing_count bigint;
begin
    for account_row in
        select distinct n.user_id
          from new_rows n
         where n.entity_type = 'apiary'
           and not n.deleted
    loop
        perform pg_catalog.pg_advisory_xact_lock(
            pg_catalog.hashtextextended('personal-apiary-limit:' || account_row.user_id::text, 0)
        );

        account_limit := public.beekeep_resolved_apiary_limit(account_row.user_id);
        select count(*)
          into existing_count
          from public.beekeep_documents d
         where d.user_id = account_row.user_id
           and d.entity_type = 'apiary'
           and not d.deleted;

        if existing_count > account_limit then
            raise exception 'Apiary limit reached: this account has a % apiary limit.', account_limit
                using errcode = '23514';
        end if;
    end loop;

    return null;
end;
$function$;

drop trigger if exists beekeep_enforce_personal_apiary_limit_after_insert
    on public.beekeep_documents;
create trigger beekeep_enforce_personal_apiary_limit_after_insert
after insert on public.beekeep_documents
referencing new table as new_rows
for each statement execute function public.beekeep_enforce_personal_apiary_limit_after_insert();

create or replace function public.beekeep_enforce_personal_apiary_limit_after_update()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    account_row record;
    account_limit integer;
    existing_count bigint;
begin
    for account_row in
        select coalesce(n.user_id, o.user_id) as user_id
          from old_rows o
          full join new_rows n
            using (user_id, entity_type, entity_id)
         group by coalesce(n.user_id, o.user_id)
        having count(*) filter (where n.entity_type = 'apiary' and not n.deleted)
             > count(*) filter (where o.entity_type = 'apiary' and not o.deleted)
    loop
        perform pg_catalog.pg_advisory_xact_lock(
            pg_catalog.hashtextextended('personal-apiary-limit:' || account_row.user_id::text, 0)
        );

        account_limit := public.beekeep_resolved_apiary_limit(account_row.user_id);
        select count(*)
          into existing_count
          from public.beekeep_documents d
         where d.user_id = account_row.user_id
           and d.entity_type = 'apiary'
           and not d.deleted;

        if existing_count > account_limit then
            raise exception 'Apiary limit reached: this account has a % apiary limit.', account_limit
                using errcode = '23514';
        end if;
    end loop;

    return null;
end;
$function$;

drop trigger if exists beekeep_enforce_personal_apiary_limit_after_update
    on public.beekeep_documents;
create trigger beekeep_enforce_personal_apiary_limit_after_update
after update on public.beekeep_documents
referencing old table as old_rows new table as new_rows
for each statement execute function public.beekeep_enforce_personal_apiary_limit_after_update();

create or replace function public.beekeep_enforce_workspace_apiary_limit_after_insert()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    workspace_row record;
    owner_id uuid;
    account_limit integer;
    existing_count bigint;
begin
    for workspace_row in
        select distinct n.workspace_id
          from new_rows n
         where n.entity_type = 'apiary'
           and not n.deleted
    loop
        select w.owner_user_id
          into owner_id
          from public.beekeep_workspaces w
         where w.id = workspace_row.workspace_id
           and not w.is_personal;

        if not found then
            -- Personal workspace documents use beekeep_documents and its own quota trigger.
            continue;
        end if;

        perform pg_catalog.pg_advisory_xact_lock(
            pg_catalog.hashtextextended('workspace-apiary-limit:' || workspace_row.workspace_id::text, 0)
        );

        account_limit := public.beekeep_resolved_apiary_limit(owner_id);
        select count(*)
          into existing_count
          from public.beekeep_workspace_documents d
         where d.workspace_id = workspace_row.workspace_id
           and d.entity_type = 'apiary'
           and not d.deleted;

        if existing_count > account_limit then
            raise exception 'Apiary limit reached: this team workspace has a % apiary limit.', account_limit
                using errcode = '23514';
        end if;
    end loop;

    return null;
end;
$function$;

drop trigger if exists beekeep_enforce_workspace_apiary_limit_after_insert
    on public.beekeep_workspace_documents;
create trigger beekeep_enforce_workspace_apiary_limit_after_insert
after insert on public.beekeep_workspace_documents
referencing new table as new_rows
for each statement execute function public.beekeep_enforce_workspace_apiary_limit_after_insert();

create or replace function public.beekeep_enforce_workspace_apiary_limit_after_update()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
declare
    workspace_row record;
    owner_id uuid;
    account_limit integer;
    existing_count bigint;
begin
    for workspace_row in
        select coalesce(n.workspace_id, o.workspace_id) as workspace_id
          from old_rows o
          full join new_rows n
            using (workspace_id, entity_type, entity_id)
         group by coalesce(n.workspace_id, o.workspace_id)
        having count(*) filter (where n.entity_type = 'apiary' and not n.deleted)
             > count(*) filter (where o.entity_type = 'apiary' and not o.deleted)
    loop
        select w.owner_user_id
          into owner_id
          from public.beekeep_workspaces w
         where w.id = workspace_row.workspace_id
           and not w.is_personal;

        if not found then
            continue;
        end if;

        perform pg_catalog.pg_advisory_xact_lock(
            pg_catalog.hashtextextended('workspace-apiary-limit:' || workspace_row.workspace_id::text, 0)
        );

        account_limit := public.beekeep_resolved_apiary_limit(owner_id);
        select count(*)
          into existing_count
          from public.beekeep_workspace_documents d
         where d.workspace_id = workspace_row.workspace_id
           and d.entity_type = 'apiary'
           and not d.deleted;

        if existing_count > account_limit then
            raise exception 'Apiary limit reached: this team workspace has a % apiary limit.', account_limit
                using errcode = '23514';
        end if;
    end loop;

    return null;
end;
$function$;

drop trigger if exists beekeep_enforce_workspace_apiary_limit_after_update
    on public.beekeep_workspace_documents;
create trigger beekeep_enforce_workspace_apiary_limit_after_update
after update on public.beekeep_workspace_documents
referencing old table as old_rows new table as new_rows
for each statement execute function public.beekeep_enforce_workspace_apiary_limit_after_update();

revoke all on function public.beekeep_enforce_personal_apiary_limit_after_insert()
    from public, anon, authenticated;
revoke all on function public.beekeep_enforce_personal_apiary_limit_after_update()
    from public, anon, authenticated;
revoke all on function public.beekeep_enforce_workspace_apiary_limit_after_insert()
    from public, anon, authenticated;
revoke all on function public.beekeep_enforce_workspace_apiary_limit_after_update()
    from public, anon, authenticated;

-- Keep the existing trigger names but align their checks and messages with Commercial.
create or replace function public.beekeep_enforce_ultimate_for_workspace_owner()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if new.is_personal = false
       and not public.beekeep_user_has_active_commercial(new.owner_user_id) then
        raise exception 'An active BeeKeep Commercial subscription is required to create a team workspace.'
            using errcode = '42501';
    end if;
    return new;
end;
$function$;

create or replace function public.beekeep_enforce_ultimate_for_workspace_invitation()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if new.status = 'pending'
       and not public.beekeep_workspace_owner_has_active_commercial(new.workspace_id) then
        raise exception 'The team workspace owner must have an active BeeKeep Commercial subscription to invite teammates.'
            using errcode = '42501';
    end if;
    return new;
end;
$function$;

drop trigger if exists beekeep_ultimate_workspace_invitation_guard
    on public.beekeep_workspace_invitations;
create trigger beekeep_ultimate_workspace_invitation_guard
before insert or update on public.beekeep_workspace_invitations
for each row execute function public.beekeep_enforce_ultimate_for_workspace_invitation();

revoke all on function public.beekeep_enforce_ultimate_for_workspace_owner()
    from public, anon, authenticated;
revoke all on function public.beekeep_enforce_ultimate_for_workspace_invitation()
    from public, anon, authenticated;

-- The invitation acceptance function is SECURITY DEFINER, so it must check the owner's
-- trusted Commercial metadata itself; the invitee is not a workspace member yet.
create or replace function public.beekeep_accept_workspace_invitation(invite_token text)
returns table (
    workspace_id uuid,
    workspace_name text,
    workspace_role text
)
language plpgsql
security definer
set search_path = 'public', 'extensions', 'pg_temp'
as $function$
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

-- SECURITY DEFINER management RPCs must not remain an alternate write path when the
-- workspace owner downgrades. This applies to invited admins as well as the owner.
create or replace function public.beekeep_update_workspace_member_role(
    target_workspace_id uuid,
    target_user_id uuid,
    new_role text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_role text;
    target_role text;
begin
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

create or replace function public.beekeep_remove_workspace_member(
    target_workspace_id uuid,
    target_user_id uuid
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_role text;
    target_role text;
begin
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

create or replace function public.beekeep_cancel_workspace_invitation(target_invitation_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    invite_row public.beekeep_workspace_invitations%rowtype;
    caller_role text;
begin
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

create or replace function public.beekeep_delete_workspace(target_workspace_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $function$
declare
    caller_id uuid := auth.uid();
    target_is_personal boolean;
    caller_role text;
begin
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

-- The current RLS SELECT policy on shared documents is intentionally unchanged. Members
-- can continue to view/export existing team data when the owner loses Commercial access.
-- Existing INSERT/UPDATE RLS policies already call the compatibility helper above, which
-- now checks the canonical Commercial entitlement for the workspace owner.

commit;
