-- Tweak autoanalze and vacuum settings to make sure it kicks in reasonably quickly after pruning.
alter table common_sequenced_events
    set (
        autovacuum_vacuum_scale_factor = 0.0,
        autovacuum_vacuum_threshold = 10000,
        autovacuum_vacuum_cost_limit = 2000,
        autovacuum_vacuum_cost_delay = 5,
        autovacuum_vacuum_insert_scale_factor = 0.0,
        autovacuum_vacuum_insert_threshold = 100000,
        autovacuum_analyze_scale_factor = 0.0,
        autovacuum_analyze_threshold = 1000000
        );
