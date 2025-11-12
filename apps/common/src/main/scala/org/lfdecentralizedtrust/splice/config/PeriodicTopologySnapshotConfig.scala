// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.digitalasset.canton.config.NonNegativeFiniteDuration

final case class PeriodicTopologySnapshotConfig(
    location: TopologySnapshotConfig,
    backupInterval: NonNegativeFiniteDuration,
)
