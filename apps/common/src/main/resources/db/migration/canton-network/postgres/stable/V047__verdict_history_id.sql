-- at the point this migration is applied, this is only relevant for CI clusters
truncate table scan_verdict_transaction_view_store cascade;
truncate table scan_verdict_store cascade;
drop index scan_verdict_update_id;
drop index scan_verdict_mi_rt;

alter table scan_verdict_store add column history_id bigint not null;

-- Index for efficient querying by migration and record time (with the usual mandatory history_id)
create index scan_verdict_hi_mi_rt on scan_verdict_store (history_id, migration_id, record_time);
-- unique index = unique constraint; update_ids are globally unique
create unique index scan_verdict_hi_ui on scan_verdict_store (history_id, update_id);
