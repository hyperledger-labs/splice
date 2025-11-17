CREATE TABLE validator_internal_config
(
    store_id int not null,
    config_key   text  not null,
    config_value jsonb not null,
    foreign key (store_id) references store_descriptors(id)
    CONSTRAINT uc_validator_internal_config PRIMARY KEY (config_key)
);
