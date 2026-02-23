ALTER TABLE update_history_transactions ADD COLUMN external_transaction_hash bytea;

-- To avoid long migration waits, the index is created asynchronously by the application (SqlIndexInitializationTrigger)
