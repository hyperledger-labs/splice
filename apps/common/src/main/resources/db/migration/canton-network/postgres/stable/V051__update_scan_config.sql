ALTER TABLE validator_internal_config
    ADD COLUMN store_id integer;

ALTER TABLE validator_internal_config
DROP CONSTRAINT uc_validator_internal_config;

ALTER TABLE validator_internal_config
    ALTER COLUMN store_id SET NOT NULL;

ALTER TABLE validator_internal_config
    ADD CONSTRAINT uc_validator_internal_config
        PRIMARY KEY (config_key, store_id);

ALTER TABLE validator_internal_config
    ADD CONSTRAINT fk_store_id
        FOREIGN KEY (store_id)
            REFERENCES store_descriptors(id);
