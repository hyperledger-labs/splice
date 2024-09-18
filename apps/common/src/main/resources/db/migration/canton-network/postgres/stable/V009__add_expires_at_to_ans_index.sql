drop index scan_acs_store_sid_mid_den_tpo;
create index scan_acs_store_sid_mid_den_tpo_exp
    on scan_acs_store (store_id, migration_id, template_id_qualified_name, ans_entry_name text_pattern_ops, contract_expires_at)
    where ans_entry_name is not null and contract_expires_at is not null;

drop index scan_acs_store_sid_mid_deo_den;
create index scan_acs_store_sid_mid_deo_den_exp
    on scan_acs_store (store_id, migration_id, template_id_qualified_name, ans_entry_owner, ans_entry_name, contract_expires_at)
    where ans_entry_owner is not null and ans_entry_name is not null and contract_expires_at is not null;

drop index dso_acs_store_sid_mid_tid_cen;
create index dso_acs_store_sid_mid_tid_cen_exp
    on dso_acs_store (store_id, migration_id, template_id_qualified_name, ans_entry_name, contract_expires_at)
    where ans_entry_name is not null and contract_expires_at is not null;
