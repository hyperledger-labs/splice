-- Only SVs that joined the network at any point in the initial migration have all import updates
-- This statement was already part of the migration script V036__backfilling_import_updates.sql.
-- It needs to be executed again because Scala code for backfilling import updates
-- was reverted and re-applied between these two migrations.
update update_history_backfilling as bf
set import_updates_complete = true
where
    bf.complete = true and
    bf.joining_migration_id = (
        select min(migration_id)
        from update_history_transactions as tx
        where bf.history_id = tx.history_id
    );
