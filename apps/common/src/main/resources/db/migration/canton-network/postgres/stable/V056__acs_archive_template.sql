-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

create table acs_store_archived_template(
    like acs_store_template including all,

    -- reestablish foreign key constraint as that one is not copied by the LIKE statement above
    foreign key (store_id) references store_descriptors(id),

    -- record_time of the transaction that archived the contract, in micros since epoch.
    archived_at bigint not null
);

create index acs_store_archived_template_temporal
    on acs_store_archived_template (store_id, migration_id, template_id_qualified_name, created_at, archived_at);
