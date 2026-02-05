ALTER TABLE update_history_transactions ADD COLUMN external_transaction_hash bytea;

-- Creating the 'updt_hist_tran_hi_eth' index on a production-sized database (~150M rows) can take ~300s.
-- To avoid long migration waits, the index is created asynchronously by the application. The SQL is left commented for reference.

-- create index updt_hist_tran_hi_eth on update_history_transactions (history_id, external_transaction_hash) where external_transaction_hash is not null;
