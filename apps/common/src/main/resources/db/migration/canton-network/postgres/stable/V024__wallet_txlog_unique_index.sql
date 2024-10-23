-- Replace the unique index, from one including the migration id to one that does not.
-- This works because transaction ids are unique across migrations so event ids should also be.
alter table user_wallet_txlog_store drop constraint user_wallet_txlog_store_store_id_migration_id_tx_log_id_eve_key;

create unique index user_wallet_txlog_store_si_ti_ei_key
    on user_wallet_txlog_store (store_id, tx_log_id, event_id);