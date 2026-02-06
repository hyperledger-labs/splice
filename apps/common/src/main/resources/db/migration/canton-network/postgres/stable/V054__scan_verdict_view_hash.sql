-- Add view_hash column to scan_verdict_transaction_view_store for correlation with sequencer traffic data
-- Column is nullable since earlier data was ingested without view_hash
alter table scan_verdict_transaction_view_store add column view_hash text;
