// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.data

import com.digitalasset.canton.sequencing.SubscriptionLivenessLimits as SubscriptionLivenessLimitsInternal

object SubscriptionLivenessLimitsAccess {
  implicit class `SubscriptionLivenessLimits access`(
      private val self: SubscriptionLivenessLimits
  ) extends AnyVal {
    def toInternal: SubscriptionLivenessLimitsInternal = self.toInternal
  }
}
