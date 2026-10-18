-- Fix the workspace roster RPC on Supabase, where auth.users.email and
-- beekeep_workspace_members.role are varchar but the function contract returns text.
-- Explicit casts prevent Postgres from rejecting every roster request at runtime.

begin;

create or replace function public.beekeep_list_workspace_members(target_workspace_id uuid)
returns table (
    workspace_id uuid,
    user_id uuid,
    email text,
    username text,
    role text,
    joined_at timestamptz,
    is_current_user boolean
)
language plpgsql
stable
security definer
set search_path = public, pg_temp
as $function$
begin
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

commit;
