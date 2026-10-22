// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto

import com.google.protobuf.ByteString

object SigningPublicKeyAccess {
  implicit class `SigningPublicKey access`(private val self: SigningPublicKey) extends AnyVal {
    def key: ByteString = self.key
  }
}
