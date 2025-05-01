create table txlog_backfilling_status
(
    store_id               int not null,
    backfilling_complete   boolean not null,

    constraint txlog_backfilling_status_pkey primary key (store_id),
    constraint txlog_backfilling_status_store_id_fkey foreign key (store_id) references store_descriptors(id)
);

create table txlog_first_ingested_update
(

    store_id               int not null,
    synchronizer_id        text not null,
    migration_id           int not null,
    record_time            bigint not null,

    constraint txlog_first_ingested_update_pkey primary key (store_id, migration_id, synchronizer_id),
    constraint txlog_first_ingested_update_store_id_fkey foreign key (store_id) references store_descriptors(id)
);

-- Indexes for fast iteration over txlog entries by record time
create index txlog_store_sid_mid_did_rt
    on txlog_store_template (store_id, migration_id, domain_id, record_time);
create index scan_txlog_store_sid_mid_did_rt
    on scan_txlog_store (store_id, migration_id, domain_id, record_time);
create index user_wallet_txlog_store_sid_mid_did_rt
    on user_wallet_txlog_store (store_id, migration_id, domain_id, record_time);
