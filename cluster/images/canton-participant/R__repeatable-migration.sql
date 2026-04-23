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
alter table lapi_filter_activate_stakeholder
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
alter table lapi_filter_deactivate_stakeholder
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
alter table lapi_filter_various_witness
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
alter table lapi_filter_activate_witness
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
alter table lapi_filter_deactivate_witness
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
alter table lapi_events_various_witnessed
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
alter table par_contracts
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
