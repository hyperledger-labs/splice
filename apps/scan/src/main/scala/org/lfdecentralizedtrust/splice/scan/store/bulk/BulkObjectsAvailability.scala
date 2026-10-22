// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.data.CantonTimestamp

sealed trait BulkObjectsAvailability

object BulkObjectsAvailability {
  case object Available extends BulkObjectsAvailability
  case object Processing extends BulkObjectsAvailability
  case object Backfilling extends BulkObjectsAvailability

  def of(
      progress: CantonTimestamp,
      requested: CantonTimestamp,
      firstOwnSegmentStart: Option[CantonTimestamp],
  ): BulkObjectsAvailability =
    if (progress >= requested) Available
    else if (firstOwnSegmentStart.exists(requested <= _)) Backfilling
    else Processing
}
