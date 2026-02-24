-- Remove the view_hash column from scan_verdict_transaction_view_store, as it was added
-- with the wrong type text instead of bytea.
alter table scan_verdict_transaction_view_store drop column view_hash;
