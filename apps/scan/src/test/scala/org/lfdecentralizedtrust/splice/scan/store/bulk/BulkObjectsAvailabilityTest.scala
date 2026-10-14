// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.bulk

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.scan.store.bulk.BulkObjectsAvailability.{
  Available,
  Backfilling,
  Processing,
}
import org.scalatest.wordspec.AnyWordSpec

import java.time.Duration

class BulkObjectsAvailabilityTest extends AnyWordSpec with BaseTest {

  private val firstOwnSegmentStart = CantonTimestamp.Epoch.plus(Duration.ofDays(10))
  private val nothingHeld = CantonTimestamp.MinValue

  "BulkObjectsAvailability" should {
    "be backfilling up to and including the first own segment start while nothing is held" in {
      BulkObjectsAvailability.of(
        nothingHeld,
        firstOwnSegmentStart,
        Some(firstOwnSegmentStart),
      ) shouldBe
        Backfilling
      BulkObjectsAvailability.of(
        nothingHeld,
        firstOwnSegmentStart.minus(Duration.ofDays(1)),
        Some(firstOwnSegmentStart),
      ) shouldBe Backfilling
    }

    "be available before the first own segment start once the copy reached the requested time" in {
      BulkObjectsAvailability.of(
        firstOwnSegmentStart,
        firstOwnSegmentStart,
        Some(firstOwnSegmentStart),
      ) shouldBe Available
    }

    "be processing right after the first own segment start" in {
      BulkObjectsAvailability.of(
        firstOwnSegmentStart,
        firstOwnSegmentStart.plusMillis(1),
        Some(firstOwnSegmentStart),
      ) shouldBe Processing
    }

    "be processing while the history start is not known yet" in {
      BulkObjectsAvailability.of(nothingHeld, firstOwnSegmentStart, None) shouldBe Processing
    }

    "never be backfilling for a Scan whose history starts at genesis" in {
      BulkObjectsAvailability.of(
        nothingHeld,
        firstOwnSegmentStart,
        Some(CantonTimestamp.MinValue),
      ) shouldBe Processing
    }
  }
}
