CREATE TABLE validator_scan_config
(
    id       SERIAL PRIMARY KEY,

    sv_name  text NOT NULL,
    scan_url text NOT NULL,

    CONSTRAINT uc_scan_config UNIQUE (scan_url, sv_name)
);
