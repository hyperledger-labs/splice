alter table dso_acs_store
  add column member_traffic_domain text;
alter table scan_acs_store
    add column member_traffic_domain text;
create index dso_acs_store_sid_mid_tid_mtm_mtd
    on dso_acs_store (store_id, migration_id, template_id_qualified_name, member_traffic_member, member_traffic_domain)
    where member_traffic_member is not null;
drop index dso_acs_store_sid_mid_tid_mtm;
create index scan_acs_store_sid_mid_tid_mtm_mtd
    on scan_acs_store (store_id, migration_id, template_id_qualified_name, member_traffic_member, member_traffic_domain)
    where member_traffic_member is not null;
drop index scan_acs_store_sid_mid_tid_mtm;
