// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.data

import com.digitalasset.canton.sequencing.SequencerConnectionPoolDelays as SequencerConnectionPoolDelaysInternal

object SequencerConnectionPoolDelaysAccess {
  implicit class `SequencerConnectionPoolDelays access`(
      private val self: SequencerConnectionPoolDelays
  ) extends AnyVal {
    def toInternal: SequencerConnectionPoolDelaysInternal = self.toInternal
  }
}
