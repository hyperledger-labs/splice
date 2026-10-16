// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto.provider.jce

import com.digitalasset.nonempty.NonEmpty
import com.digitalasset.canton.crypto.{
  Signature,
  SigningAlgorithmSpec,
  SigningError,
  SigningKeyUsage,
  SigningPrivateKey,
}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString

object JcePureCryptoAccess {
  implicit class `JcePureCrypto access`(private val self: JcePureCrypto) extends AnyVal {
    def signBytes(
        bytes: ByteString,
        signingKey: SigningPrivateKey,
        usage: NonEmpty[Set[SigningKeyUsage]],
        signingAlgorithmSpec: SigningAlgorithmSpec = self.signingAlgorithmSpecs.default,
    )(implicit traceContext: TraceContext): Either[SigningError, Signature] =
      self.signBytes(bytes, signingKey, usage, signingAlgorithmSpec)
  }
}
