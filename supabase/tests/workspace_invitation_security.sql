-- Stage 2 workspace invitation and role authorization assertions.
-- Runs in a disposable PostgreSQL service after the Stage 1 tests/migrations.

create temporary table stage2_workspace_test_context (
    key text primary key,
    value text
);
grant select, insert, update on stage2_workspace_test_context to authenticated;

set role authenticated;
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);

insert into stage2_workspace_test_context(key, value)
select 'workspace_id', id::text
from public.beekeep_create_workspace('Shared Stage 2 Test Workspace');

insert into stage2_workspace_test_context(key, value)
select 'invitation',
       invitation_id::text || '|' || invite_token
from public.beekeep_create_workspace_invitation(
    (select value::uuid from stage2_workspace_test_context where key = 'workspace_id'),
    'owner-two@example.test',
    'member'
);

do $owner_assertions$
declare
    workspace_value uuid := (select value::uuid from stage2_workspace_test_context where key = 'workspace_id');
    invite_record text := (select value from stage2_workspace_test_context where key = 'invitation');
    invitation_id uuid := split_part(invite_record, '|', 1)::uuid;
    token_value text := split_part(invite_record, '|', 2);
begin
    if workspace_value is null or invitation_id is null or not ((length(token_value) = 8 and translate(token_value, 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789', '') = '') or (length(token_value) = 64 and translate(token_value, '0123456789abcdef', '') = '')) then
        raise exception 'Workspace creation did not return a valid invitation code';
    end if;

    if not exists (
        select 1 from public.beekeep_workspace_invitations
        where id = invitation_id and workspace_id = workspace_value
          and lower(email) = 'owner-two@example.test' and status = 'pending'
          and token_hash <> token_value
    ) then
        raise exception 'Invitation was not stored as a pending hashed token';
    end if;
end;
$owner_assertions$;

-- The invitation must only be accepted by the invited email account.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
select * from public.beekeep_accept_workspace_invitation(
    split_part((select value from stage2_workspace_test_context where key = 'invitation'), '|', 2)
);

do $accept_assertions$
declare workspace_value uuid := (select value::uuid from stage2_workspace_test_context where key = 'workspace_id');
begin
    if not exists (
        select 1 from public.beekeep_workspace_members
        where workspace_id = workspace_value
          and user_id = '00000000-0000-0000-0000-000000000002'
          and role = 'member'
    ) then
        raise exception 'Valid invitation did not add the invited member with the requested role';
    end if;
end;
$accept_assertions$;

-- Owner can promote an invited member to admin.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select public.beekeep_update_workspace_member_role(
    (select value::uuid from stage2_workspace_test_context where key = 'workspace_id'),
    '00000000-0000-0000-0000-000000000002',
    'admin'
);

-- Admin cannot grant admin to another account.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000002', false);
do $admin_role_assertions$
declare denied boolean := false; error_message text;
begin
    begin
        perform * from public.beekeep_create_workspace_invitation(
            (select value::uuid from stage2_workspace_test_context where key = 'workspace_id'),
            'future-admin@example.test',
            'admin'
        );
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%Only the workspace owner can invite another admin%' then
            raise exception 'Unexpected admin-invite failure: %', error_message;
        end if;
        denied := true;
    end;
    if not denied then raise exception 'Admin unexpectedly created an admin invitation'; end if;
end;
$admin_role_assertions$;

-- Owner creates a second invitation; a non-member must not be able to cancel it.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
insert into stage2_workspace_test_context(key, value)
select 'pending_invitation',
       invitation_id::text
from public.beekeep_create_workspace_invitation(
    (select value::uuid from stage2_workspace_test_context where key = 'workspace_id'),
    'new-user@example.test',
    'viewer'
);

select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000003', false);
do $non_member_assertions$
declare denied boolean := false; error_message text;
begin
    begin
        perform public.beekeep_cancel_workspace_invitation(
            (select value::uuid from stage2_workspace_test_context where key = 'pending_invitation')
        );
    exception when others then
        get stacked diagnostics error_message = message_text;
        if error_message not like '%Only owners and admins can cancel invitations%' then
            raise exception 'Unexpected non-member cancellation failure: %', error_message;
        end if;
        denied := true;
    end;
    if not denied then raise exception 'Non-member unexpectedly cancelled a workspace invitation'; end if;
end;
$non_member_assertions$;

-- The owner can cancel the pending invitation.
select set_config('request.jwt.claim.sub', '00000000-0000-0000-0000-000000000001', false);
select public.beekeep_cancel_workspace_invitation(
    (select value::uuid from stage2_workspace_test_context where key = 'pending_invitation')
);

reset role;

select 'Stage 2 workspace invitation and authorization tests passed' as result;
