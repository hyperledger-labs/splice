// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store.db

import com.digitalasset.canton.BaseTest
import org.lfdecentralizedtrust.splice.scan.store.db.ActivityIngestionMetaCheck.*
import org.lfdecentralizedtrust.splice.scan.store.db.DbAppActivityRecordStore.AppActivityRecordMetaT
import org.scalatest.wordspec.AnyWordSpec

class CheckMetaVersionsTest extends AnyWordSpec with BaseTest {

  private def meta(code: Int, user: Int): Some[AppActivityRecordMetaT] =
    Some(
      AppActivityRecordMetaT(
        historyId = 1L,
        codeVersion = code,
        userVersion = user,
        startedIngestingAt = 0L,
        earliestIngestedRound = 0L,
      )
    )

  "checkMetaVersions" should {

    "return InsertMeta when no meta row exists" in {
      checkMetaVersions(None, runningCode = 1, runningUser = 0) shouldBe InsertMeta
    }

    "return Resume when versions match" in {
      checkMetaVersions(meta(1, 0), runningCode = 1, runningUser = 0) shouldBe Resume
    }

    "return InsertMeta when code version is higher" in {
      checkMetaVersions(meta(1, 0), runningCode = 2, runningUser = 0) shouldBe InsertMeta
    }

    "return InsertMeta when user version is higher" in {
      checkMetaVersions(meta(1, 0), runningCode = 1, runningUser = 1) shouldBe InsertMeta
    }

    "return InsertMeta when both versions are higher" in {
      checkMetaVersions(meta(1, 0), runningCode = 2, runningUser = 1) shouldBe InsertMeta
    }

    "return DowngradeDetected when code version is lower" in {
      checkMetaVersions(meta(2, 0), runningCode = 1, runningUser = 0) shouldBe
        DowngradeDetected(runningCode = 1, runningUser = 0, storedCode = 2, storedUser = 0)
    }

    "return DowngradeDetected when user version is lower" in {
      checkMetaVersions(meta(1, 1), runningCode = 1, runningUser = 0) shouldBe
        DowngradeDetected(runningCode = 1, runningUser = 0, storedCode = 1, storedUser = 1)
    }

    "return DowngradeDetected when code is higher but user is lower" in {
      checkMetaVersions(meta(1, 1), runningCode = 2, runningUser = 0) shouldBe
        DowngradeDetected(runningCode = 2, runningUser = 0, storedCode = 1, storedUser = 1)
    }

    "return DowngradeDetected when code is lower but user is higher" in {
      checkMetaVersions(meta(2, 0), runningCode = 1, runningUser = 1) shouldBe
        DowngradeDetected(runningCode = 1, runningUser = 1, storedCode = 2, storedUser = 0)
    }
  }
}
