-- Drop unique indexes on (history_id, migration_id, participant_offset)
drop index updt_hist_tran_unique;
drop index updt_hist_assi_unique;
drop index updt_hist_unas_unique;

-- Replace by unique indexes on (history_id, migration_id, update_id)
create unique index updt_hist_tran_hi_mi_ui_u on update_history_transactions (history_id, migration_id, domain_id, update_id);
create unique index updt_hist_assi_hi_mi_ui_u on update_history_assignments (history_id, migration_id, domain_id, update_id);
create unique index updt_hist_unas_hi_mi_ui_u on update_history_unassignments (history_id, migration_id, domain_id, update_id);

drop table update_history_backfilling;
create table update_history_backfilling
(
    history_id              int not null primary key,

    -- true if the history is complete, i.e., it starts with the very first update that founded the network
    complete                boolean not null,

    -- reference to the first update ingested, before backfilling started
    joining_migration_id    int not null,
    joining_domain_id       text not null,
    joining_update_id       text not null,

    foreign key (history_id) references update_history_descriptors(id),

    foreign key (history_id, joining_migration_id, joining_domain_id, joining_update_id)
        references update_history_transactions(history_id, migration_id, domain_id, update_id)
);
