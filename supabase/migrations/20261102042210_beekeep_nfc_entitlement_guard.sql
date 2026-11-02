-- Enforce Commercial-only NFC assignments for personal cloud documents.
-- Existing assignments remain readable after downgrade; only active assignments can be
-- inserted/edited while the account has a trusted Commercial entitlement.
begin;

create or replace function public.beekeep_enforce_personal_nfc_entitlement()
returns trigger
language plpgsql
security definer
set search_path = ''
as $function$
begin
    if new.entity_type = 'nfc_assignment'
       and not new.deleted
       and not public.beekeep_user_has_active_commercial(new.user_id) then
        raise exception 'NFC scanning and tag management require an active BeeKeep Commercial subscription.'
            using errcode = '42501';
    end if;

    return new;
end;
$function$;

revoke all on function public.beekeep_enforce_personal_nfc_entitlement()
    from public, anon, authenticated;

drop trigger if exists beekeep_personal_nfc_entitlement_guard
    on public.beekeep_documents;
create trigger beekeep_personal_nfc_entitlement_guard
before insert or update on public.beekeep_documents
for each row execute function public.beekeep_enforce_personal_nfc_entitlement();

commit;
