-- For append only table, tweak the autovacuum configs
-- Configs are set based on deployments with regular usage
-- scale factor is set to 0 as append only tables are always increasing thus we would run autovacuum less frequently over time

-- based on an average of 1 inserts per second
ALTER TABLE scan_txlog_store SET (autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 20000);
ALTER TABLE dso_txlog_store SET (autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 20000);
ALTER TABLE user_wallet_txlog_store SET (autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 20000);

ALTER TABLE round_party_totals SET (autovacuum_analyze_scale_factor = 0,  autovacuum_analyze_threshold = 20000);
