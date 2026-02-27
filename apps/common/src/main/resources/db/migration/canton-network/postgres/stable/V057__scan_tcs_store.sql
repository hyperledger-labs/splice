-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

create table acs_store_archived_test(
    like acs_store_template including all,

    -- reestablish foreign key constraint as that one is not copied by the LIKE statement above
    foreign key (store_id) references store_descriptors(id),

    -- record_time of the transaction that archived the contract, in micros since epoch.
    archived_at bigint not null
);

create index acs_store_archived_test_temporal
    on acs_store_archived_test (store_id, migration_id, template_id_qualified_name, created_at) include (archived_at);

create table scan_tcs_store_active
(
    like acs_store_template including all,

    -- reestablish foreign key constraint as that one is not copied by the LIKE statement above
    foreign key (store_id) references store_descriptors (id),

    -- index columns
    ----------------

    -- the round of the OpenMiningRound
    round                       bigint,

    -- the provider partyid of a FeaturedAppRight contract
    featured_app_right_provider text
);

-- temporal query support: created_at filtering for point-in-time lookups on the live table
create index scan_tcs_store_active_temporal
    on scan_tcs_store_active (store_id, migration_id, template_id_qualified_name, created_at);

create table scan_tcs_store_archived
(
    like scan_tcs_store_active including all,

    -- reestablish foreign key constraint as that one is not copied by the LIKE statement above
    foreign key (store_id) references store_descriptors (id),

    -- record_time of the transaction that archived the contract, in micros since epoch.
    archived_at bigint not null
);

-- temporal query support: created_at + archived_at filtering for point-in-time lookups on the archive table
create index scan_tcs_store_archived_temporal
    on scan_tcs_store_archived (store_id, migration_id, template_id_qualified_name, created_at) include (archived_at);
