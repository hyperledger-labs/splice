-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Index on round column for efficient lookups by round number.
create index scan_rewards_reference_store_active_round
    on scan_rewards_reference_store_active (store_id, migration_id, round)
    where round is not null;

create index scan_rewards_reference_store_archived_round
    on scan_rewards_reference_store_archived (store_id, migration_id, round)
    where round is not null;
