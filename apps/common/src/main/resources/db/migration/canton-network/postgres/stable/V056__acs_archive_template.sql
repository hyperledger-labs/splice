-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Template for ACS stores that use soft-delete instead of hard-delete.
-- When a contract is archived (consumed), instead of deleting the row,
-- the archived_at column is set to the transaction's record_time.
-- Queries on the standard MultiDomainAcsStore APIs filter on archived_at IS NULL
-- to return only active contracts. Temporal queries use created_at and archived_at
-- to reconstruct the ACS at any historical record_time.
create table acs_store_archive_template(
    like acs_store_template including all,

    -- reestablish foreign key constraint as that one is not copied by the LIKE statement above
    foreign key (store_id) references store_descriptors(id),

    -- record_time of the transaction that archived the contract, in micros since epoch.
    -- NULL means the contract is active.
    archived_at bigint
);
