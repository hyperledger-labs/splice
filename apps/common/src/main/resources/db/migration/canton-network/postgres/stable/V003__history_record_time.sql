-- Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Add a record time to the txlog store tables
alter table txlog_store_template add column record_time bigint not null default 0;
create index txlog_store_template_sid_mid_rc
    on txlog_store_template (store_id, migration_id, record_time);

-- Altering the table template does not retroactively update the existing tables.
alter table scan_txlog_store add column record_time bigint not null default 0;
alter table dso_txlog_store add column record_time bigint not null default 0;
alter table user_wallet_txlog_store add column record_time bigint not null default 0;

create index scan_txlog_store_sid_mid_rc
    on scan_txlog_store (store_id, migration_id, record_time);
create index dso_txlog_store_sid_mid_rc
    on dso_txlog_store (store_id, migration_id, record_time);
create index user_wallet_txlog_store_sid_mid_rc
    on user_wallet_txlog_store(store_id, migration_id, record_time);
