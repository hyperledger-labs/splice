// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.admin.http

import cats.syntax.either.*
import com.digitalasset.canton.BaseTest
import io.circe.Json
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  DamlValueEncoding,
  EventHistoryItem,
  FeatureSupportResponse,
  UpdateHistoryRequestV2,
  UpdateHistoryResponseV2,
  UpdateHistoryTransactionV2,
  UpdateHistoryItemV2,
}
import org.lfdecentralizedtrust.splice.http.v0.scan.{ScanHandler, ScanResource}
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.concurrent.duration.*

/** Proves that the **generated** guardrail route code drops only the `external_transaction_hash`
  * null field from JSON responses via the custom `Encoder` instances provided by
  * [[ScanJsonSupport]], while retaining all other null fields.
  */
class GeneratedScanRouteDropNullsTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  private def responseBodyString: String =
    response.entity.toStrict(3.seconds).futureValue.data.utf8String

  "Generated ScanResource routes" should {

    "drop external_transaction_hash null from getUpdateHistoryV2 responses" in {
      val handler = mock[ScanHandler[Unit]]
      val txV2 = UpdateHistoryTransactionV2(
        updateId = "update-1",
        migrationId = 0L,
        workflowId = "wf",
        recordTime = "2024-01-01T00:00:00Z",
        synchronizerId = "domain::1234",
        effectiveAt = "2024-01-01T00:00:00Z",
        rootEventIds = Vector.empty,
        eventsById = SortedMap.empty,
        externalTransactionHash = None, // <-- this should be dropped
      )
      // ignoring the result since it's obviously a Future.successful stub.
      val _ = doReturn(
        Future.successful(
          ScanResource.GetUpdateHistoryV2ResponseOK(
            UpdateHistoryResponseV2(
              transactions = Vector(UpdateHistoryItemV2(txV2))
            )
          )
        )
      ).when(handler)
        .getUpdateHistoryV2(any[ScanResource.GetUpdateHistoryV2Response.type])(
          any[UpdateHistoryRequestV2]
        )(any[Unit])

      // The generated Routes.scala imports ScanJsonSupport._ which provides
      // custom Encoder instances that omit null OmitNullString fields
      val route = ScanResource.routes(handler, _ => provide(()))

      Post(
        "/api/scan/v2/updates",
        HttpEntity(ContentTypes.`application/json`, """{"page_size": 1}"""),
      ) ~> route ~> check {
        status shouldBe StatusCodes.OK
        val json =
          io.circe.parser.parse(responseBodyString).valueOr(e => fail(s"Invalid JSON: $e"))

        // external_transaction_hash was None, so must be absent from the JSON
        val txJson = json.hcursor.downField("transactions").downArray
        txJson.downField("external_transaction_hash").focus shouldBe None
        txJson.downField("update_id").as[String].value shouldBe "update-1"
      }
    }

    "retain other null fields in targeted response types (only external_transaction_hash is dropped)" in {
      val handler = mock[ScanHandler[Unit]]

      // EventHistoryItem has several Option fields (update, verdict, etc.) — they should
      // remain as null in JSON. Only external_transaction_hash (nested inside update)
      // should be dropped when null.
      val txV2 = UpdateHistoryTransactionV2(
        updateId = "update-2",
        migrationId = 0L,
        workflowId = "wf",
        recordTime = "2024-01-01T00:00:00Z",
        synchronizerId = "domain::1234",
        effectiveAt = "2024-01-01T00:00:00Z",
        rootEventIds = Vector.empty,
        eventsById = SortedMap.empty,
        externalTransactionHash = None,
      )
      val eventItem = EventHistoryItem(
        update = Some(UpdateHistoryItemV2(txV2)),
        verdict = None, // should be preserved as null
        trafficSummary = None, // should be preserved as null
        appActivityRecords = None, // should be preserved as null
      )
      // ignoring the result since it's obviously a Future.successful stub.
      val _ = doReturn(
        Future.successful(
          ScanResource.GetEventByIdResponseOK(eventItem)
        )
      ).when(handler)
        .getEventById(any[ScanResource.GetEventByIdResponse.type])(
          any[String],
          any[Option[DamlValueEncoding]],
        )(
          any[Unit]
        )

      val route = ScanResource.routes(handler, _ => provide(()))

      Get("/api/scan/v0/events/update-2") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val json =
          io.circe.parser.parse(responseBodyString).valueOr(e => fail(s"Invalid JSON: $e"))

        // Other null fields in EventHistoryItem should be present as null
        json.hcursor.downField("verdict").focus shouldBe Some(Json.Null)
        json.hcursor.downField("traffic_summary").focus shouldBe Some(Json.Null)
        json.hcursor.downField("app_activity_records").focus shouldBe Some(Json.Null)

        // But external_transaction_hash inside the nested transaction should be absent
        val txJson = json.hcursor.downField("update")
        txJson.downField("external_transaction_hash").focus shouldBe None
        txJson.downField("update_id").as[String].value shouldBe "update-2"
      }
    }

    "keep null values for non-targeted response types (featureSupport)" in {
      val handler = mock[ScanHandler[Unit]]
      when(handler.featureSupport(any[ScanResource.FeatureSupportResponse.type])()(any[Unit]))
        .thenReturn(
          Future.successful(
            ScanResource.FeatureSupportResponseOK(FeatureSupportResponse(dummy = None))
          )
        )

      val route = ScanResource.routes(handler, _ => provide(()))

      Get("/api/scan/v0/feature-support") ~> route ~> check {
        status shouldBe StatusCodes.OK
        val json =
          io.circe.parser.parse(responseBodyString).valueOr(e => fail(s"Invalid JSON: $e"))

        // FeatureSupportResponse has no specific marshaller,
        // so the "dummy" field IS present as null
        json.hcursor.downField("dummy").focus shouldBe Some(Json.Null)
      }
    }
  }
}
