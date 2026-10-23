// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.topology.admin.grpc

import com.digitalasset.canton.topology.SynchronizerId

object SynchronizerAccess {
  implicit class `Synchronizer access`(private val self: TopologyStoreId.Synchronizer)
      extends AnyVal {
    def logicalSynchronizerId: SynchronizerId = self.logicalSynchronizerId
  }
}
