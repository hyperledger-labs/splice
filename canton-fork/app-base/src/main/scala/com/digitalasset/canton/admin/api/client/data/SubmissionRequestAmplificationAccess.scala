// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.admin.api.client.data

import com.digitalasset.canton.sequencing.SubmissionRequestAmplification as SubmissionRequestAmplificationInternal

object SubmissionRequestAmplificationAccess {
  implicit class `SubmissionRequestAmplification access`(
      private val self: SubmissionRequestAmplification
  ) extends AnyVal {
    def toInternal: SubmissionRequestAmplificationInternal = self.toInternal
  }
}
