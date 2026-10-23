alter table dso_acs_store
  add column governance_lock_type text,
  add column governance_lock_subject text;

alter table scan_acs_store
  add column governance_lock_type text,
  add column governance_lock_subject text;
