create table update_history_backfilling
(
    history_id              int not null,

    migration_id            bigint not null,

    complete                boolean not null,

    primary key (history_id, migration_id),
    foreign key (history_id) references update_history_descriptors(id)
);
