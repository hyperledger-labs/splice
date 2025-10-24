-- Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

-- For ValidatorLivenessActivityRecord weight field
ALTER TABLE dso_acs_store ADD COLUMN validator_liveness_weight numeric;
