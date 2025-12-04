CREATE TABLE validator_internal_config
(
    config_key   text  not null,
    config_value jsonb not null,
    CONSTRAINT uc_validator_internal_config PRIMARY KEY (config_key)
);
