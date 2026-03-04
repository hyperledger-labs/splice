-- Might exist, might not, depending on whether the version defining it ran or not.
DROP INDEX IF EXISTS updt_hist_tran_hi_eth;
ALTER TABLE update_history_transactions DROP COLUMN external_transaction_hash;
