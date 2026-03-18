// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api

import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.BaseTest
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

class HttpRequestLoggerTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  private val apiLoggingConfig = ApiLoggingConfig()

  private def loggerDirective = HttpRequestLogger(
    apiLoggingConfig,
    loggerFactory,
  )

  private def rejectionHandler = HttpRequestLogger.loggingRejectionHandler(
    apiLoggingConfig,
    loggerFactory,
  )

  /** Simulates the behavior: a single HttpRequestLogger at the top level
    * with handleRejections(loggingRejectionHandler) INSIDE the logger.
    * The logger logs the request and uses mapResponse to log the response.
    * handleRejections seals the route, converting rejections to HTTP responses
    * so mapResponse sees all outcomes. loggingRejectionHandler additionally
    * logs the rejection status code during conversion.
    */
  private def route: Route =
    loggerDirective {
      handleRejections(rejectionHandler) {
        concat(
          pathPrefix("api" / "admin") {
            complete(StatusCodes.OK, "admin")
          },
          pathPrefix("api" / "app") {
            complete(StatusCodes.OK, "app")
          },
        )
      }
    }

  "HttpRequestLogger" should {

    "log exactly one 'received request' and one response for a matching route" in {
      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.DEBUG))(
        {
          Get("/api/app") ~> route ~> check {
            status shouldBe StatusCodes.OK
            responseAs[String] shouldBe "app"
          }
        },
        { logEntries =>
          // Exactly one "received request"
          val receivedLogs = logEntries.filter(_.message.contains("received request"))
          receivedLogs should have size 1

          // Exactly one response log from mapResponse
          val respondingLogs = logEntries.filter(_.message.contains("Responding with status code"))
          respondingLogs should have size 1

          // No rejection log — the route matched
          val rejectedLogs = logEntries.filter(_.message.contains("rejected with status code"))
          rejectedLogs shouldBe empty
        },
      )
    }

    "log rejection with status code for a non-matching route" in {
      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.DEBUG))(
        {
          Get("/api/unknown") ~> route ~> check {
            status shouldBe StatusCodes.NotFound
          }
        },
        { logEntries =>
          // One "received request"
          val receivedLogs = logEntries.filter(_.message.contains("received request"))
          receivedLogs should have size 1

          // loggingRejectionHandler logs the rejection
          val rejectedLogs = logEntries.filter(_.message.contains("rejected with status code"))
          rejectedLogs should have size 1
          rejectedLogs.head.message should include("404")

          // mapResponse also sees the rejection-converted response (it's inside handleRejections)
          val respondingLogs = logEntries.filter(_.message.contains("Responding with status code"))
          respondingLogs should have size 1
          respondingLogs.head.message should include("404")
        },
      )
    }

    "one log entry per request when methods conflict across siblings" in {
      val newStyleMethodRoute: Route =
        loggerDirective {
          handleRejections(rejectionHandler) {
            concat(
              pathPrefix("api" / "data") {
                post {
                  complete(StatusCodes.OK, "posted")
                }
              },
              pathPrefix("api" / "data") {
                get {
                  complete(StatusCodes.OK, "got")
                }
              },
            )
          }
        }

      loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.DEBUG))(
        {
          Get("/api/data") ~> newStyleMethodRoute ~> check {
            status shouldBe StatusCodes.OK
            responseAs[String] shouldBe "got"
          }
        },
        { logEntries =>
          // Exactly one "received request"
          val receivedLogs = logEntries.filter(_.message.contains("received request"))
          receivedLogs should have size 1

          // One clean response
          val respondingLogs = logEntries.filter(_.message.contains("Responding with status code"))
          respondingLogs should have size 1

          // No rejection — the GET route matched
          val rejectedLogs = logEntries.filter(_.message.contains("rejected with status code"))
          rejectedLogs shouldBe empty
        },
      )
    }
  }
}
