begin;

-- Standalone Feed/Treatment/Harvest entries use their own target types and action
-- types. Apiary-wide batch entries continue to use the existing batch target.
alter table public.beekeep_workspace_action_log
    drop constraint if exists beekeep_workspace_action_log_action_type_check,
    drop constraint if exists beekeep_workspace_action_log_target_type_check;

alter table public.beekeep_workspace_action_log
    add constraint beekeep_workspace_action_log_action_type_check
    check (action_type in (
        'inspection_created',
        'inspection_edited',
        'apiary_added',
        'apiary_edited',
        'apiary_deleted',
        'hive_added',
        'hive_edited',
        'hive_deleted',
        'feeding_added',
        'feeding_edited',
        'treatment_added',
        'treatment_edited',
        'harvest_added',
        'harvest_edited',
        'mass_feed',
        'mass_treatment',
        'mass_harvest'
    )),
    add constraint beekeep_workspace_action_log_target_type_check
    check (target_type in ('inspection', 'apiary', 'hive', 'feeding', 'treatment', 'harvest', 'batch'));

commit;
