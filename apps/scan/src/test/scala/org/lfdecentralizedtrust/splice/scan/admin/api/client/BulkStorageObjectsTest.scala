// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.api.client

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.scan.admin.api.client.commands.HttpScanAppClient.BulkStorageObjects
import org.lfdecentralizedtrust.splice.store.S3BucketConnection.ObjectKeyAndChecksum
import org.scalatest.wordspec.AnyWordSpec

import java.time.{Instant, ZoneOffset}

class BulkStorageObjectsTest extends AnyWordSpec with BaseTest {

  private val folder = "2026-01-02T00:00:00Z~2026-01-03T00:00:00Z"
  private val objectKey = s"$folder/updates_compact_json_0.zstd"
  private val encodedKey =
    java.net.URLEncoder.encode(objectKey, java.nio.charset.StandardCharsets.UTF_8)

  private def ref(peerUrl: String, digest: String) =
    definitions.BulkStorageObjectRef(
      url = s"$peerUrl/api/scan/v0/history/bulk/download/$encodedKey",
      digest = digest,
    )

  "BulkStorageObjects" should {
    "recover the same object key from the download urls of different peers" in {
      val fromSv1 = ref("https://scan.sv-1.example.com", "d-0")
      val fromSv2 = ref("http://scan-app.sv-2:5012", "d-0")
      BulkStorageObjects.decodeObjectRefs(Seq(fromSv1)) shouldBe
        Right(Seq(ObjectKeyAndChecksum(objectKey, "d-0")))
      BulkStorageObjects.decodeObjectRefs(Seq(fromSv1)) shouldBe
        BulkStorageObjects.decodeObjectRefs(Seq(fromSv2))
    }

    "reject urls without a download path, with an empty key or with anything after the key" in {
      forAll(
        Seq(
          "https://scan.example.com/other/path",
          "https://scan.example.com/api/scan/v0/history/bulk/download/",
          s"https://scan.example.com/api/scan/v0/history/bulk/download/$encodedKey?sig=abc",
          s"https://scan.example.com/api/scan/v0/history/bulk/download/$encodedKey/extra",
        )
      ) { url =>
        inside(BulkStorageObjects.objectKeyFromDownloadUrl(url)) { case Left(error) =>
          error should include(url)
        }
      }
    }

    "convert both listing responses" in {
      val recordTime = Instant.parse("2026-01-03T00:00:00Z")
      BulkStorageObjects.snapshotObjects(
        definitions.ListBulkAcsSnapshotObjectsResponse(
          recordTime.atOffset(ZoneOffset.UTC),
          Vector(ref("https://scan.example.com", "s-0")),
        )
      ) shouldBe Right(
        BulkStorageObjects.SnapshotObjects(
          CantonTimestamp.assertFromInstant(recordTime),
          Seq(ObjectKeyAndChecksum(objectKey, "s-0")),
        )
      )
      BulkStorageObjects.updateObjectsPage(
        definitions.ListBulkUpdateHistoryObjectsResponse(
          Vector(ref("https://scan.example.com", "d-0")),
          Some(folder),
        )
      ) shouldBe Right(
        BulkStorageObjects.UpdateObjectsPage(
          Seq(ObjectKeyAndChecksum(objectKey, "d-0")),
          Some(folder),
        )
      )
    }
  }
}
