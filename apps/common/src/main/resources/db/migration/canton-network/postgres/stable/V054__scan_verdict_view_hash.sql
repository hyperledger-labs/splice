-- Add view_hash column to scan_verdict_transaction_view_store for correlation with sequencer traffic data
-- Column is nullable since earlier data was ingested without view_hash
alter table scan_verdict_transaction_view_store add column view_hash text;

-- Index for efficient view_hash lookups in correlation queries
create index scan_verdict_view_hash on scan_verdict_transaction_view_store (view_hash);
