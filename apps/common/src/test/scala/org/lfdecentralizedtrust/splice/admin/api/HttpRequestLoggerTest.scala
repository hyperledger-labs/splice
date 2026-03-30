// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.api

import com.digitalasset.canton.config.ApiLoggingConfig
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.BaseTest
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.{RejectionHandler, Route}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

class HttpRequestLoggerTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  private val apiLoggingConfig = ApiLoggingConfig()

  private def loggerDirective = HttpRequestLogger(
    apiLoggingConfig,
    loggerFactory,
  )

  /** Simulates the production behavior: HttpRequestLogger wraps handleRejections.
    * handleRejections seals the route with the default RejectionHandler, converting
    * rejections into HTTP responses so mapResponse (inside HttpRequestLogger) sees
    * all outcomes and logs exactly one "Responding with status code" per request —
    * whether matched or rejected.
    */
  private def route: Route =
    loggerDirective {
      handleRejections(RejectionHandler.default) {
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
          forExactly(1, logEntries)(_.message should include("received request"))

          // Exactly one response log from mapResponse
          forExactly(1, logEntries)(_.message should include("Responding with status code"))
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
          forExactly(1, logEntries)(_.message should include("received request"))

          // mapResponse logs exactly one outcome — the rejection-converted 404 response
          forExactly(1, logEntries) { entry =>
            entry.message should include("Responding with status code")
            entry.message should include("404")
          }
        },
      )
    }

    "one log entry per request when methods conflict across siblings" in {
      val newStyleMethodRoute: Route =
        loggerDirective {
          handleRejections(RejectionHandler.default) {
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
          forExactly(1, logEntries)(_.message should include("received request"))

          // One clean response
          forExactly(1, logEntries)(_.message should include("Responding with status code"))
        },
      )
    }
  }
}
