package org.lfdecentralizedtrust.splice.integration.tests.runbook

import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.scalatest.Assertion
import org.slf4j.event.Level

import scala.concurrent.{Future, blocking}
import scala.util.control.NonFatal
import scala.util.{Failure, Try}

class RateLimitPreflightIntegrationTest extends IntegrationTestWithSharedEnvironment {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  "Scan ACS requests are rate limited" in { implicit env =>
    forAll(Table("scan", env.scans.remote*)) { scanCli =>
      val dsoParty = scanCli.getDsoPartyId()
      rateLimitIsEnforced(
        10, {
          scanCli.getAcsSnapshot(
            // Dummy party that doesn't exist to avoid creating load
            PartyId.tryCreate(
              "rate-limit-party",
              dsoParty.namespace,
            ),
            None,
          )
        },
      )
    }
  }

  "Other scan requests are not rate limited" in { implicit env =>
    forAll(Table("scan", env.scans.remote*)) { scanCli =>
      rateLimitIsNotEnforced(
        10, {
          scanCli.getDsoPartyId()
        },
      )
    }
  }

  def rateLimitIsNotEnforced(limit: Int, call: => Unit)(implicit
      env: SpliceTestConsoleEnvironment
  ): Assertion = {
    val results = collectResponses(limit + 10, call)
    allWereSuccessfull(results)
  }
  def rateLimitIsEnforced(limit: Int, call: => Unit)(implicit
      env: SpliceTestConsoleEnvironment
  ): Assertion = {
    val results = loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.ERROR))(
      collectResponses(limit, call),
      forAll(_)(
        // This hits the Canton limit on concurrent requests
        _.message should include(
          "Reached the limit of concurrent requests for com.digitalasset.canton.admin.participant.v30.ParticipantRepairService/ExportAcsOld"
        )
      ),
    )
    // Note: failures are expected due to the Canton rate limiter.
    forAtLeast(1, results) {
      _ shouldBe a[scala.util.Success[?]]
    }
    // This now hits istio rate limit
    assertThrowsAndLogsCommandFailures(
      call,
      entry => entry.message should include("HTTP 429 Too Many Requests"),
    )
  }

  private def allWereSuccessfull(results: Seq[Try[Unit]]) = {
    results.collect { case Failure(NonFatal(exception)) =>
      exception
    } should be(empty)
  }

  private def collectResponses(limit: Int, call: => Unit)(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    import env.executionContext
    MonadUtil
      .parTraverseWithLimit(PositiveInt.MaxValue)(
        Seq.fill(limit)(())
      )(_ => {
        Future {
          blocking {
            Try {
              call
            }
          }
        }
      })
      .futureValue
  }

}
