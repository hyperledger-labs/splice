-- For append only table, tweak the autovacuum configs
-- Configs are set based on deployments with regular usage
-- scale factor is set to 0 as append only tables are always increasing thus we would run autovacuum less frequently over time

-- based on an average of 1 inserts per second
ALTER TABLE update_history_exercises SET (autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 20000);

ALTER TABLE update_history_creates SET (autovacuum_analyze_scale_factor = 0,  autovacuum_analyze_threshold = 20000);

ALTER TABLE update_history_transactions SET (autovacuum_analyze_scale_factor = 0, autovacuum_analyze_threshold = 20000);
