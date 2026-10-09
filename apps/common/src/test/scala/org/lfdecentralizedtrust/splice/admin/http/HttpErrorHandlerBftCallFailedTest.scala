// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.admin.http

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.lfdecentralizedtrust.splice.environment.BftCallFailed
import org.scalatest.wordspec.AnyWordSpec

class HttpErrorHandlerBftCallFailedTest extends AnyWordSpec with BaseTest with ScalatestRouteTest {

  private val errorHandler = new HttpErrorHandler(loggerFactory)

  private def failingWith(failure: BftCallFailed): Route =
    errorHandler.exceptionsDirective(TraceContext.empty)(failWith(failure))

  private val expectedStatus = Seq[(BftCallFailed, StatusCode)](
    new BftCallFailed.NotYetAvailable("not yet") -> StatusCodes.ServiceUnavailable,
    new BftCallFailed.NeverAvailable("never") -> StatusCodes.NotFound,
    new BftCallFailed.Disagreement("disagree") -> StatusCodes.BadGateway,
    new BftCallFailed.NotEnoughScans("not enough") -> StatusCodes.BadGateway,
  )

  "HttpErrorHandler" should {
    expectedStatus.foreach { case (failure, status) =>
      s"answer a ${failure.getClass.getSimpleName} BFT failure with $status" in {
        Get("/") ~> failingWith(failure) ~> check {
          response.status shouldBe status
          responseAs[String] should include(failure.getMessage)
        }
      }
    }
  }
}
