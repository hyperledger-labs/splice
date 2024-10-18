// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.kernel.Semigroup
import com.digitalasset.canton.data.CantonTimestamp

final case class DomainRecordTimeRange(
    min: CantonTimestamp,
    max: CantonTimestamp,
)

object DomainRecordTimeRange {
  implicit def domainTimeRangeSemigroupUnion: Semigroup[DomainRecordTimeRange] =
    (x: DomainRecordTimeRange, y: DomainRecordTimeRange) =>
      DomainRecordTimeRange(x.min min y.min, x.max max y.max)
}
