package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import org.apache.pekko.http.scaladsl.model.Uri
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithIsolatedEnvironment
import org.lfdecentralizedtrust.splice.scan.config.{BulkStorageBackfillingConfig, BulkStorageConfig}
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfigs.scanStorageConfigV1
import org.lfdecentralizedtrust.splice.scan.store.historystart.HistoryStart
import org.lfdecentralizedtrust.splice.store.{HasS3Mock, S3BucketConnectionForTests}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.{
  LocalSequencerConnectionsTrigger,
  ReceiveSvRewardCouponTrigger,
}
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}

import java.time.Duration
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

class ScanNewNetworkBackfillingTimeBasedIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with TimeTestUtil
    with WalletTestUtil
    with HasExecutionContext
    with HasActorSystem
    with HasS3Mock {

  private val scanNames = (1 to 4).map(i => s"sv${i}Scan")
  private def bucket(scanName: String, kind: String): String = s"${scanName.toLowerCase}-$kind"

  override val initialBuckets: Seq[String] =
    scanNames.flatMap(name => Seq(bucket(name, "staging"), bucket(name, "committed")))

  override protected def runEventHistorySanityCheck: Boolean = false
  override protected def runUpdateHistorySanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[LocalSequencerConnectionsTrigger]
            .withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Scan)(
          _.copy(acsSnapshotTriggerPollingInterval = Some(NonNegativeFiniteDuration.ofHours(1)))
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs((name, scanConfig) =>
          scanConfig.copy(
            updateHistoryBackfillEnabled =
              if (name == "sv2Scan") false else scanConfig.updateHistoryBackfillEnabled,
            bulkStorage = BulkStorageConfig(
              snapshotPollingInterval = NonNegativeFiniteDuration.ofSeconds(5),
              updatesPollingInterval = NonNegativeFiniteDuration.ofSeconds(5),
              bftRetryInterval = NonNegativeFiniteDuration.ofSeconds(1),
              staging = Some(s3ConfigMock(bucket(name, "staging"))),
              committed = Some(s3ConfigMock(bucket(name, "committed"))),
              backfilling = BulkStorageBackfillingConfig(
                enabled = true,
                pollingInterval = NonNegativeFiniteDuration.ofSeconds(5),
              ),
            ),
            publicUrl = Some(Uri(s"http://${name.toLowerCase}.example.com")),
          )
        )(config)
      )
      .withManualStart

  private def objectsIn(bucketName: String): Map[String, String] = {
    val connection = new S3BucketConnectionForTests(s3ConfigMock(bucketName), loggerFactory)
    val keys = connection.listObjects.futureValue.contents().asScala.map(_.key()).toSeq
    connection.getChecksums(keys).futureValue.map(o => o.key -> o.checksum).toMap
  }

  private def folderRange(key: String): (CantonTimestamp, CantonTimestamp) =
    scanStorageConfigV1.getStartAndEndTimestampsForFolder(
      scanStorageConfigV1.getSegmentFolderOfObjectKey(key)
    ) match {
      case Right(range) => range
      case Left(err) => fail(s"Unexpected object key $key: $err")
    }

  private def isSnapshot(key: String): Boolean = key.contains("/ACS_")

  private def snapshotTime(key: String): CantonTimestamp = folderRange(key)._1

  private def segmentEnd(key: String): CantonTimestamp = folderRange(key)._2

  private def updatesBefore(firstOwnSegmentStart: CantonTimestamp)(key: String): Boolean =
    !isSnapshot(key) && segmentEnd(key) <= firstOwnSegmentStart

  private def snapshotAtOrBefore(firstOwnSegmentStart: CantonTimestamp)(key: String): Boolean =
    isSnapshot(key) && snapshotTime(key) <= firstOwnSegmentStart

  private def copiedByBackfill(firstOwnSegmentStart: CantonTimestamp)(key: String): Boolean =
    updatesBefore(firstOwnSegmentStart)(key) || snapshotAtOrBefore(firstOwnSegmentStart)(key)

  "copy the founder's first segments to an SV that joins inside the first segment" in {
    implicit env =>
      clue("Start sv1, the founder") {
        startAllSync(sv1ScanBackend, sv1Backend, sv1ValidatorBackend)
      }

      clue("sv1 records genesis as its history start and completes its backfill without copying") {
        eventually() {
          sv1ScanBackend.appState.historyStart.get.futureValue shouldBe Some(HistoryStart.Genesis)
          sv1ScanBackend.appState.bulkStorage.value.backfillingProgress.isComplete.futureValue shouldBe true
        }
      }

      clue("Start sv2, without update history backfilling") {
        sv2Backend.startSync()
        sv2ScanBackend.startSync()
      }

      val firstOwnSegmentStart =
        clue("sv2 records the DSO party hosting time as its history start") {
          eventually() {
            inside(sv2ScanBackend.appState.historyStart.get.futureValue) {
              case Some(start @ HistoryStart.From(_)) =>
                start.firstOwnSegmentStart(scanStorageConfigV1)
            }
          }
        }

      clue(s"Advance past sv2's first own segment start $firstOwnSegmentStart") {
        val target = firstOwnSegmentStart.plus(Duration.ofHours(4))
        val hours = Duration.between(getLedgerTime.toInstant, target.toInstant).toHours + 1
        (1L to hours).foreach(_ => advanceTime(Duration.ofHours(1)))
      }

      def toCopyIn(bucketName: String): Map[String, String] =
        objectsIn(bucketName).filter { case (key, _) =>
          copiedByBackfill(firstOwnSegmentStart)(key)
        }

      clue(
        "sv1 commits the segments before sv2's first own segment, its only peer sv2 answering never"
      ) {
        eventually(timeUntilSuccess = 2.minutes) {
          val committed = toCopyIn(bucket("sv1Scan", "committed"))
          committed.keys.exists(updatesBefore(firstOwnSegmentStart)) shouldBe true
          committed.keys.exists(key =>
            isSnapshot(key) && snapshotTime(key) == firstOwnSegmentStart
          ) shouldBe true
        }
      }

      clue(
        "sv2 copies exactly the objects sv1 committed into its staging bucket and marks the backfill complete"
      ) {
        eventually(timeUntilSuccess = 2.minutes) {
          sv2ScanBackend.appState.bulkStorage.value.backfillingProgress.isComplete.futureValue shouldBe true
          toCopyIn(bucket("sv2Scan", "staging")) shouldBe toCopyIn(bucket("sv1Scan", "committed"))
        }
      }
  }
}
