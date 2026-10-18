-- Explicit, atomic copy of the signed-in user's private cloud records into a team workspace.
-- Personal data is never published merely because an invitation is accepted.
begin;

create or replace function public.beekeep_copy_personal_documents_to_workspace(target_workspace_id uuid)
returns table(copied_count integer)
language plpgsql
security definer
set search_path = public, pg_temp
as $function$
declare
    caller_id uuid := auth.uid();
    inserted_count integer := 0;
begin
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

    -- Refuse the entire copy if any private record key already exists in this team.
    -- This makes the operation atomic and avoids silently overwriting a team's record.
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
        workspace_id,
        entity_type,
        entity_id,
        payload,
        updated_at,
        deleted,
        device_id,
        updated_by
    )
    select
        target_workspace_id,
        d.entity_type,
        d.entity_id,
        d.payload,
        d.updated_at,
        d.deleted,
        d.device_id,
        caller_id
    from public.beekeep_documents d
    where d.user_id = caller_id;

    get diagnostics inserted_count = row_count;
    return query select inserted_count;
end;
$function$;

revoke all on function public.beekeep_copy_personal_documents_to_workspace(uuid) from public, anon;
grant execute on function public.beekeep_copy_personal_documents_to_workspace(uuid) to authenticated;

commit;
