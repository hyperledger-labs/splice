alter table update_history_backfilling
    add column import_updates_complete boolean not null default false;

-- Only SVs that joined the network at any point in the initial migration have all import updates
update update_history_backfilling as bf
set import_updates_complete = true
where
    bf.complete = true and
    bf.joining_migration_id = (
        select min(migration_id)
        from update_history_transactions as tx
        where bf.history_id = tx.history_id
    );

-- Partial index for import updates, so that we can quickly sort them.
-- Other updates are sorted by either (domain_id, record_time) or (record_time, domain_id), depending on the use case.
create index updt_hist_tran_hi_mi_ui_import_updates
    on update_history_transactions (history_id, migration_id, update_id)
    where record_time = -62135596800000000; -- This is CantonTimestamp.MinValue, which is used for import updates.
