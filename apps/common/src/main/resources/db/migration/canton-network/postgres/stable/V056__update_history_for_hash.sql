ALTER TABLE update_history_transactions ADD COLUMN external_transaction_hash bytea;

-- To avoid long migration waits, the index is created asynchronously by the application (SqlIndexInitializationTrigger)
-- The canonical DDL is kept commented here for reference and future use if we move away from app-managed index creation or need manual backfill:
-- create index updt_hist_tran_hi_eth on update_history_transactions (history_id, external_transaction_hash) where external_transaction_hash is not null;
