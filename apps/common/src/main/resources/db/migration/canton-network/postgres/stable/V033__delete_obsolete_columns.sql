drop index validator_acs_store_sid_mid_tid_acv;
drop index validator_acs_store_sid_mid_tid_arv;
drop index validator_acs_store_sid_mid_tid_jh;

alter table validator_acs_store
 drop column app_configuration_version,
 drop column app_configuration_name,
 drop column app_release_version,
 drop column json_hash;

drop index scan_acs_store_sid_mid_tid_icrn;

alter table scan_acs_store
 drop column import_crate_receiver;

drop index dso_acs_store_sid_mid_tid_icr;

alter table dso_acs_store
 drop column import_crate_receiver;
