package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.scan.config.SequencerTrafficIngestionConfig
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson

import com.digitalasset.canton.config.NonNegativeFiniteDuration

class ScanEventHistoryExtraDataIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with ScanTestUtil
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs((_, scanConfig) =>
          scanConfig.copy(
            mediatorVerdictIngestion = scanConfig.mediatorVerdictIngestion.copy(
              restartDelay = NonNegativeFiniteDuration.ofMillis(500)
            ),
            sequencerTrafficIngestion = SequencerTrafficIngestionConfig(enabled = true),
            serveTrafficSummaries = true,
          )
        )(config)
      )

  private val pageLimit = 1000

  "should ingest and serve traffic summaries" in { implicit env =>
    startAllSync(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

    val _ = onboardAliceAndBob()

    val cursorBeforeTap = eventuallySucceeds() { latestEventHistoryCursor(sv1ScanBackend) }

    val (_, eventsWithTrafficSummary) = actAndCheck(
      "alice taps",
      aliceWalletClient.tap(1),
    )(
      "event history includes events with traffic summaries",
      _ => {
        val eventHistory = sv1ScanBackend.getEventHistory(
          count = pageLimit,
          after = Some(cursorBeforeTap),
          encoding = CompactJson,
        )
        eventHistory should not be empty

        val withSummary = eventHistory.filter(_.trafficSummary.isDefined)
        withSummary should not be empty
        withSummary
      },
    )

    withClue("Traffic summary structure should be valid") {
      eventsWithTrafficSummary.foreach { item =>
        item.trafficSummary.foreach { summary =>
          summary.totalTrafficCost should be > 0L
          summary.envelopeTrafficCosts should not be empty
          summary.envelopeTrafficCosts.foreach { env =>
            env.trafficCost should be > 0L
          }
        }
      }
    }

    withClue("Traffic summaries should be on verdict-only events") {
      eventsWithTrafficSummary.foreach { item =>
        item.verdict shouldBe defined
        item.update shouldBe empty
      }
    }

    withClue("Traffic summary should also be present via getEventById") {
      val verdictItems = eventsWithTrafficSummary.filter(_.verdict.isDefined)
      verdictItems should not be empty

      Seq(verdictItems.head, verdictItems.last).distinct.foreach { item =>
        item.verdict.fold(fail("Expected verdict")) { verdict =>
          val eventById = sv1ScanBackend
            .getEventById(verdict.updateId, Some(CompactJson))
            .getOrElse(fail(s"Expected event for update id ${verdict.updateId}"))
          eventById.trafficSummary shouldBe defined
        }
      }
    }
  }
}
