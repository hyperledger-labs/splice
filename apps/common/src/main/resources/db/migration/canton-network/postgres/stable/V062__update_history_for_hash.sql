ALTER TABLE update_history_transactions
    ADD COLUMN external_transaction_hash bytea
        CONSTRAINT external_transaction_hash_not_empty CHECK (length(external_transaction_hash) > 0);

-- To avoid long migration waits, the index is created asynchronously by the application (SqlIndexInitializationTrigger)
