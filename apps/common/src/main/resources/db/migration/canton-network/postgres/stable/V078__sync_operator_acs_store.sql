-- ACS store of the sync operator app, which only ingests MemberTraffic.
create table sync_operator_acs_store(
    like acs_store_template including all,

    -- reestablish foreign key constraint as that one is not copied by the LIKE statement above
    foreign key (store_id) references store_descriptors(id),

    -- index columns
    ----------------

    -- the member id in a MemberTraffic
    member_traffic_member         text,

    -- the synchronizer id in a MemberTraffic. Constant for this store, which is scoped to a single
    -- synchronizer, but kept so the query and index match the SV and scan stores.
    member_traffic_domain         text,

    -- the purchased traffic in a MemberTraffic
    total_traffic_purchased       bigint
);

create index sync_operator_acs_store_sid_mid_pn_tid_mtm_mtd
    on sync_operator_acs_store (store_id, migration_id, package_name, template_id_qualified_name,
                                member_traffic_member, member_traffic_domain)
    where member_traffic_member is not null;
