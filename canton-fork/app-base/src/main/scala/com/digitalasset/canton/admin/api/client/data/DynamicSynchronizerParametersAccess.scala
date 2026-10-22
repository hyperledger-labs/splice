// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.data

import com.digitalasset.canton.config

object DynamicSynchronizerParametersAccess {
  implicit class `DynamicSynchronizerParameters access`(
      private val self: DynamicSynchronizerParameters
  ) extends AnyVal {
    def compatibleWithNewPreparationTimeRecordTimeTolerance(
        newPreparationTimeRecordTimeTolerance: config.NonNegativeFiniteDuration
    ): Boolean =
      self.compatibleWithNewPreparationTimeRecordTimeTolerance(
        newPreparationTimeRecordTimeTolerance
      )
  }
}
