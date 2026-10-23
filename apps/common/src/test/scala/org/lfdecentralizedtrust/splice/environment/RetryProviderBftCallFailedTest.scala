// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.concurrent.FutureSupervisor
import org.scalatest.wordspec.AsyncWordSpec

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future

class RetryProviderBftCallFailedTest extends AsyncWordSpec with BaseTest {

  private val retryProvider =
    RetryProvider(loggerFactory, timeouts, FutureSupervisor.Noop, NoOpMetricsFactory)

  private def failingFirst(failure: BftCallFailed, attempts: AtomicInteger): Future[String] =
    if (attempts.incrementAndGet() == 1) Future.failed(failure) else Future.successful("agreed")

  private val retried = Seq(
    "not yet available" -> new BftCallFailed.NotYetAvailable("not yet"),
    "a disagreement" -> new BftCallFailed.Disagreement("disagree"),
    "not enough scans" -> new BftCallFailed.NotEnoughScans("not enough"),
  )

  "RetryProvider" should {
    retried.foreach { case (name, failure) =>
      s"retry a BFT call that failed with $name" in {
        val attempts = new AtomicInteger(0)
        retryProvider
          .retryForClientCalls("bft_call", "BFT call", failingFirst(failure, attempts), logger)
          .map { result =>
            result shouldBe "agreed"
            attempts.get() shouldBe 2
          }
      }
    }

    "not retry a BFT call whose data no scan will ever have" in {
      val attempts = new AtomicInteger(0)
      retryProvider
        .retryForClientCalls(
          "bft_call",
          "BFT call",
          failingFirst(new BftCallFailed.NeverAvailable("never"), attempts),
          logger,
        )
        .failed
        .map { failure =>
          failure shouldBe a[BftCallFailed.NeverAvailable]
          attempts.get() shouldBe 1
        }
    }
  }
}
