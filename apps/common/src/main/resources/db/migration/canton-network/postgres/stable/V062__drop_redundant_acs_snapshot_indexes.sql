-- Not needed anymore after switching for incremental snapshot generation
drop index acs_snapshot_data_cid;

-- Redundant with acs_snapshot_data_all_filters by including stakeholder = DSO
drop index acs_snapshot_data_template_only_filter;
