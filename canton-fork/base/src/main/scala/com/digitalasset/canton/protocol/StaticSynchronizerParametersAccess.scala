// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.protocol

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.crypto.{
  CryptoKeyFormat,
  HashAlgorithm,
  RequiredEncryptionSpecs,
  RequiredSigningSpecs,
  SignatureFormat,
  SymmetricKeyScheme,
}
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.version.ProtocolVersion
import com.digitalasset.nonempty.NonEmpty

object StaticSynchronizerParametersAccess {
  implicit class `StaticSynchronizerParameters access`(
      private val self: StaticSynchronizerParameters
  ) extends AnyVal {

    /** Like the case class `copy`, which is inaccessible because the constructor is private. */
    def copy(
        requiredSigningSpecs: RequiredSigningSpecs = self.requiredSigningSpecs,
        requiredEncryptionSpecs: RequiredEncryptionSpecs = self.requiredEncryptionSpecs,
        requiredSymmetricKeySchemes: NonEmpty[Set[SymmetricKeyScheme]] =
          self.requiredSymmetricKeySchemes,
        requiredHashAlgorithms: NonEmpty[Set[HashAlgorithm]] = self.requiredHashAlgorithms,
        requiredCryptoKeyFormats: NonEmpty[Set[CryptoKeyFormat]] = self.requiredCryptoKeyFormats,
        requiredSignatureFormats: NonEmpty[Set[SignatureFormat]] = self.requiredSignatureFormats,
        topologyChangeDelay: NonNegativeFiniteDuration = self.topologyChangeDelay,
        enableTransparencyChecks: Boolean = self.enableTransparencyChecks,
        protocolVersion: ProtocolVersion = self.protocolVersion,
        serial: NonNegativeInt = self.serial,
        synchronizerLimits: SynchronizerLimits = self.synchronizerLimits,
    ): StaticSynchronizerParameters =
      StaticSynchronizerParameters
        .create(
          requiredSigningSpecs,
          requiredEncryptionSpecs,
          requiredSymmetricKeySchemes,
          requiredHashAlgorithms,
          requiredCryptoKeyFormats,
          requiredSignatureFormats,
          topologyChangeDelay,
          enableTransparencyChecks,
          protocolVersion,
          serial,
          synchronizerLimits,
        )
        .fold(throw _, identity)
  }
}
