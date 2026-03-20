-- Not needed anymore after switching for incremental snapshot generation.
drop index acs_snapshot_data_cid;

-- Redundant with acs_snapshot_data_all_filters by including stakeholder = DSO.
drop index acs_snapshot_data_template_only_filter;

-- Enforcing this constraint is too expensive. We'll trust our application code not to break referential integrity.
alter table acs_snapshot_data drop constraint acs_snapshot_data_create_id_fkey;
