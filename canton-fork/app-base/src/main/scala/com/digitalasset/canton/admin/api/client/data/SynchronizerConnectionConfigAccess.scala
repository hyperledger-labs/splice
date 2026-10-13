// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.data

import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig as SynchronizerConnectionConfigInternal

object SynchronizerConnectionConfigAccess {
  implicit class `SynchronizerConnectionConfig access`(
      private val self: SynchronizerConnectionConfig
  ) extends AnyVal {
    def toInternal: SynchronizerConnectionConfigInternal = self.toInternal
  }
}
