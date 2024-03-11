-- Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

alter table update_history_creates add column package_name text not null default '';
alter table update_history_assignments add column package_name text not null default '';
