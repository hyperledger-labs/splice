-- TODO(#14156) Consider if we want to make one of the existing columns more generic.
alter table scan_acs_store add column transfer_preapproval_receiver text;
create index scan_acs_store_sid_mid_tid_tpr
  on scan_acs_store (store_id, migration_id, transfer_preapproval_receiver)
  where transfer_preapproval_receiver is not null;
