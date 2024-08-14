create index updt_hist_assi_hi_mi_rt_di on update_history_assignments (history_id, migration_id, record_time, domain_id);
create index updt_hist_unas_hi_mi_rt_di on update_history_unassignments (history_id, migration_id, record_time, domain_id);
