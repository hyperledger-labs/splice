ALTER TABLE validator_internal_config
    RENAME TO key_value_store;

ALTER TABLE key_value_store
    RENAME COLUMN config_key TO key;

ALTER TABLE key_value_store
    RENAME COLUMN config_value TO value;

ALTER TABLE key_value_store
    RENAME CONSTRAINT uc_validator_internal_config TO uc_key_value_store;
