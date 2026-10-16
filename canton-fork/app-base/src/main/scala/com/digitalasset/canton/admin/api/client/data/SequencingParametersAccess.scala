// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.data

import com.digitalasset.canton.protocol
import com.digitalasset.canton.version.ProtocolVersion

object SequencingParametersAccess {
  implicit class `SequencingParameters access`(private val self: SequencingParameters)
      extends AnyVal {
    def toInternal(protocolVersion: ProtocolVersion): protocol.SequencingParameters =
      self.toInternal(protocolVersion)
  }
}
