-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- Scope unavailable parties per store: the primary key was (party), which prevented
-- multiple stores from tracking the same party independently.
alter table dso_unavailable_parties drop constraint dso_unavailable_parties_pkey;
alter table dso_unavailable_parties add primary key (store_id, party);

-- Replaced by the primary key index, which has store_id as its prefix.
drop index if exists dso_unavailable_parties_sid;
