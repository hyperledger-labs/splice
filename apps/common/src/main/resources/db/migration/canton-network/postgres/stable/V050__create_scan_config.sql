CREATE TABLE validator_scan_config
(
    sv_name       text NOT NULL,
    scan_url      text NOT NULL,
    restart_count int  Not Null

        CONSTRAINT uc_scan_config UNIQUE (scan_url, sv_name, restart_count)
);
