CREATE TABLE validator_internal_config
(
    config_key   text primary key not null,
    config_value jsonb            not null,
    CONSTRAINT uc_scan_config UNIQUE (config_key)
);
