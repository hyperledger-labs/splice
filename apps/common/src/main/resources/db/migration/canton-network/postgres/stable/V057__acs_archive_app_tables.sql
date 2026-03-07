-- Archived tables for all app-specific ACS stores, following the pattern from V056 (acs_store_archived_template).

-- user_wallet_acs_store
create table user_wallet_acs_store_archived (
    like user_wallet_acs_store including all,
    foreign key (store_id) references store_descriptors(id),
    archived_at bigint not null
);
create index user_wallet_acs_store_archived_temporal
    on user_wallet_acs_store_archived (store_id, migration_id, template_id_qualified_name, created_at, archived_at);

-- external_party_wallet_acs_store
create table external_party_wallet_acs_store_archived (
    like external_party_wallet_acs_store including all,
    foreign key (store_id) references store_descriptors(id),
    archived_at bigint not null
);
create index external_party_wallet_acs_store_archived_temporal
    on external_party_wallet_acs_store_archived (store_id, migration_id, template_id_qualified_name, created_at, archived_at);

-- validator_acs_store
create table validator_acs_store_archived (
    like validator_acs_store including all,
    foreign key (store_id) references store_descriptors(id),
    archived_at bigint not null
);
create index validator_acs_store_archived_temporal
    on validator_acs_store_archived (store_id, migration_id, template_id_qualified_name, created_at, archived_at);

-- scan_acs_store
create table scan_acs_store_archived (
    like scan_acs_store including all,
    foreign key (store_id) references store_descriptors(id),
    archived_at bigint not null
);
create index scan_acs_store_archived_temporal
    on scan_acs_store_archived (store_id, migration_id, template_id_qualified_name, created_at, archived_at);

-- sv_acs_store
create table sv_acs_store_archived (
    like sv_acs_store including all,
    foreign key (store_id) references store_descriptors(id),
    archived_at bigint not null
);
create index sv_acs_store_archived_temporal
    on sv_acs_store_archived (store_id, migration_id, template_id_qualified_name, created_at, archived_at);

-- dso_acs_store
create table dso_acs_store_archived (
    like dso_acs_store including all,
    foreign key (store_id) references store_descriptors(id),
    archived_at bigint not null
);
create index dso_acs_store_archived_temporal
    on dso_acs_store_archived (store_id, migration_id, template_id_qualified_name, created_at, archived_at);

-- splitwell_acs_store
create table splitwell_acs_store_archived (
    like splitwell_acs_store including all,
    foreign key (store_id) references store_descriptors(id),
    archived_at bigint not null
);
create index splitwell_acs_store_archived_temporal
    on splitwell_acs_store_archived (store_id, migration_id, template_id_qualified_name, created_at, archived_at);
