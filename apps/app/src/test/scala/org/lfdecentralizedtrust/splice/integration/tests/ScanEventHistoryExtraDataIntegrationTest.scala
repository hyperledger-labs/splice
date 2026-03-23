package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.config.SequencerTrafficIngestionConfig
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.http.v0.definitions
import definitions.DamlValueEncoding.members.CompactJson

class ScanEventHistoryExtraDataIntegrationTest
    extends IntegrationTest
    with ScanTestUtil
    with WalletTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs((_, scanConfig) =>
          scanConfig.copy(
            sequencerTrafficIngestion = SequencerTrafficIngestionConfig(enabled = true),
            serveTrafficSummaries = true,
          )
        )(config)
      )

  private val pageLimit = 1000

  "should ingest and serve traffic summaries" in { implicit env =>
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
          summary.envelopeTrafficSummaries should not be empty
          summary.envelopeTrafficSummaries.foreach { env =>
            env.trafficCost should be > 0L
          }
        }
      }
    }

    withClue("Traffic summary should also be present via getEventById") {
      Seq(eventsWithTrafficSummary.head, eventsWithTrafficSummary.last).distinct.foreach { item =>
        item.verdict.foreach { verdict =>
          val eventById = sv1ScanBackend
            .getEventById(verdict.updateId, Some(CompactJson))
            .getOrElse(fail(s"Expected event for update id ${verdict.updateId}"))
          eventById.trafficSummary shouldBe item.trafficSummary
        }
      }
    }
  }
}
