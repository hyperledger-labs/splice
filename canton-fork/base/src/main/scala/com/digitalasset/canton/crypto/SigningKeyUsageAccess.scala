// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.crypto

object SigningKeyUsageAccess {
  implicit class `SigningKeyUsage access`(private val self: SigningKeyUsage.type) extends AnyVal {
    def ProofOfOwnership: SigningKeyUsage = self.ProofOfOwnershipOnly.head1
  }
}
