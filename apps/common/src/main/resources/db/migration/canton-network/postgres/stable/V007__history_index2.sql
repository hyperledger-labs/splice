drop index updt_hist_tran_hi_rt_di; -- from V006
create index updt_hist_tran_hi_mi_rt_di on update_history_transactions (history_id, migration_id, record_time, domain_id);
