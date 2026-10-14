// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol

import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.version.ProtocolVersion

object StaticSynchronizerParametersAccess {
  implicit class `StaticSynchronizerParameters access`(
      private val self: StaticSynchronizerParameters
  ) extends AnyVal {

    /** Restricted variant of the case class `copy`, which is inaccessible
      * because the constructor is private.
      */
    def copy(
        topologyChangeDelay: NonNegativeFiniteDuration = self.topologyChangeDelay,
        protocolVersion: ProtocolVersion = self.protocolVersion,
    ): StaticSynchronizerParameters =
      StaticSynchronizerParameters
        .create(
          self.requiredSigningSpecs,
          self.requiredEncryptionSpecs,
          self.requiredSymmetricKeySchemes,
          self.requiredHashAlgorithms,
          self.requiredCryptoKeyFormats,
          self.requiredSignatureFormats,
          topologyChangeDelay,
          self.enableTransparencyChecks,
          protocolVersion,
          self.serial,
          self.synchronizerLimits,
        )
        .fold(throw _, identity)
  }
}
