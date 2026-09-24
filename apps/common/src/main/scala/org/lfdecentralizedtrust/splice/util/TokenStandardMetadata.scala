// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

object TokenStandardMetadata {
  val splicePrefix = "splice.lfdecentralizedtrust.org"

  val reasonMetaKey = s"$splicePrefix/reason"
  val senderMetaKey = s"$splicePrefix/sender"
  val burnedMetaKey = s"$splicePrefix/burned"

  // Mirrors "trafficPurchaseReceiver" in splice.ExternalPartyAmuletRules.daml
  val trafficPurchaseReceiver =
    "cip-xxx_traffic-purchase::1220000000000000000000000000000000000000000000000000000000000000abcd"

  val expireLockKey = "expire-lock"
}
