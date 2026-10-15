-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

alter table acs_snapshot
    add column creates_table_name text default null,
    add column stakeholders_table_name text default null,
    -- default true for all existing snapshots, any new ones will set it to false until the indexes are created
    add column indexes_created boolean default true,
  -- these values won't be set anymore
    drop constraint acs_snapshot_first_row_id_fkey,
    drop constraint acs_snapshot_last_row_id_fkey,
    alter column first_row_id drop not null,
    alter column last_row_id drop not null,
    -- ensure consistency
    add constraint legacy_or_per_snapshot check
        -- per-snapshot tables
        ((first_row_id is null and last_row_id is null and creates_table_name is not null and stakeholders_table_name is not null) or
            -- legacy table
         (first_row_id is not null and last_row_id is not null and creates_table_name is null and stakeholders_table_name is null));

create index acs_snapshot_unindexed on acs_snapshot (history_id, snapshot_record_time) where not indexes_created;

-- TODO (#7438): template ids can be interned already

-- Same as acs_incremental_snapshot_data_next,
-- but includes ALL update_history_creates data necessary to build a CreatedEvent.
create table acs_incremental_snapshot_data_next_v2
(
    -- In production, we have a separate table for each scan instance so this
    -- column will be the same for all rows. However, in tests we can have multiple
    -- scan instances writing to the same table, so we need to include it.
    snapshot_id             bigint  not null references acs_incremental_snapshot (snapshot_id),
    contract_id             text    not null,

    -- All the data necessary to reconstruct a created event
    create_arguments        jsonb  not null,
    event_id                text   not null,
    record_time             bigint not null,
    template_id_package_id  text   not null,
    -- template_id = package_name:template_id_module_name:template_id_entity_name
    package_name            text not null,
    template_id_module_name text not null,
    template_id_entity_name text not null,
    contract_key            text   null,
    created_at              bigint not null,
    -- stakeholders = array_cat(signatories, observers)
    signatories             text[] not null,
    observers               text[] not null,
    -- plus the Amulet-specific balance columns currently computed in the working table
    unlocked_amulet_balance numeric,
    locked_amulet_balance   numeric
);

-- Used for fast insert/remove by contract id
alter table acs_incremental_snapshot_data_next_v2
    add constraint acs_incremental_snapshot_data_next_v2_pk
        primary key (snapshot_id, contract_id);

-- Template table for acs_snapshot_creates_<history_id>_<record_time_epoch>.
-- This allows the code to just CREATE TABLE LIKE acs_snapshot_creates_template or acs_snapshot_stakeholders_template.
-- Design decision: we don't have a single table per (contract_id, stakeholder) in order to avoid duplicating the create_arguments.
create table acs_snapshot_creates_v1_template
(
    contract_id             text primary key,
    -- All the data necessary to reconstruct a created event
    create_arguments        jsonb  not null,
    event_id                text   not null,
    record_time             bigint not null,
    template_id_package_id  text   not null, -- the package_name is already included as part of the template_id
    contract_key            text   null,
    created_at              bigint not null,
    signatories             text[] not null,
    observers               text[] not null,
    -- plus the Amulet-specific balance columns currently computed in the working table
    unlocked_amulet_balance numeric,
    locked_amulet_balance   numeric
);

create table acs_snapshot_stakeholders_v1_template
(
    -- filtering
    stakeholder text not null,
    template_id text not null,
    -- sorting
    created_at  bigint not null,
    contract_id text not null
);

-- Necessary indexes:
-- 1) (stakeholder, template_id, created_at, contract_id) for:
--     where stakeholder=? and template_id=? (and created_at >= $after and contract_id > $after) order by created_at, contract_id
-- 2) (stakeholder, created_at, contract_id) for:
--     where stakeholder=? (and created_at >= $after and contract_id > $after) order by created_at, contract_id
