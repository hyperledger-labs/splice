// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.store

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotPaginationToken
import org.lfdecentralizedtrust.splice.scan.store.AcsSnapshotStore.QueryAcsSnapshotPaginationToken.{
  CreatedAtContractIdAcsSnapshotPaginationToken,
  RowIdQueryAcsSnapshotPaginationToken,
}
import org.lfdecentralizedtrust.splice.store.StoreTestBase

import scala.util.Try

class QueryAcsSnapshotPaginationTokenTest extends StoreTestBase with BaseTest {

  "RowIdQueryAcsSnapshotPaginationToken" should {

    "encode to base64 and decode back" in {
      val token = RowIdQueryAcsSnapshotPaginationToken(42L)
      val encoded = token.encodeToBase64
      val decoded = QueryAcsSnapshotPaginationToken.tryDecodeFromBase64(encoded)
      decoded shouldBe token
    }

    "produce different encoded values for different row ids" in {
      val token1 = RowIdQueryAcsSnapshotPaginationToken(1L)
      val token2 = RowIdQueryAcsSnapshotPaginationToken(2L)
      token1.encodeToBase64 should not equal token2.encodeToBase64
    }
  }

  "CreatedAtContractIdAcsSnapshotPaginationToken" should {

    "encode to base64 and decode back" in {
      val token = CreatedAtContractIdAcsSnapshotPaginationToken(CantonTimestamp.now(), nextCid())
      val encoded = token.encodeToBase64
      val decoded = QueryAcsSnapshotPaginationToken.tryDecodeFromBase64(encoded)
      decoded shouldBe token
    }

    "produce different encoded values for different tokens" in {
      val token1 = CreatedAtContractIdAcsSnapshotPaginationToken(CantonTimestamp.now(), nextCid())
      val token2 = CreatedAtContractIdAcsSnapshotPaginationToken(
        CantonTimestamp.now().plusSeconds(42L),
        nextCid(),
      )
      token1.encodeToBase64 should not equal token2.encodeToBase64
    }

  }

  "QueryAcsSnapshotPaginationToken.decodeFromBase64" should {

    "return Left for an invalid base64 string" in {
      val result = Try(QueryAcsSnapshotPaginationToken.tryDecodeFromBase64("not-valid-base64!!!"))
      result.isFailure should be(true)
    }

    "return Left for valid base64 but invalid JSON content" in {
      val encoded = java.util.Base64.getEncoder.encodeToString("not-a-long".getBytes("UTF-8"))
      val result = Try(QueryAcsSnapshotPaginationToken.tryDecodeFromBase64(encoded))
      result.isFailure should be(true)
    }

    "return Left for valid base64 with JSON object instead of long" in {
      val encoded =
        java.util.Base64.getEncoder.encodeToString("""{"after": 42}""".getBytes("UTF-8"))
      val result = Try(QueryAcsSnapshotPaginationToken.tryDecodeFromBase64(encoded))
      result.isFailure should be(true)
    }
  }

}
