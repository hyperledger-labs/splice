package org.lfdecentralizedtrust.splice.integration.plugins

import cats.data.Chain
import com.daml.ledger.javaapi.data.Identifier
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllScanAppConfigs_
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.console.ScanAppBackendReference
import org.lfdecentralizedtrust.splice.environment.SpliceEnvironment
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.http.v0.definitions.{AcsResponse, UpdateHistoryItemV2}
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItemV2.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryReassignment.Event.members as reassignmentMembers
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.scan.automation.AcsSnapshotTrigger
import org.lfdecentralizedtrust.splice.util.{QualifiedName, TriggerTestUtil}
import com.digitalasset.canton.ScalaFuturesWithPatience
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.SuppressingLogger
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.scan.config.ScanStorageConfigs.scanStorageConfigV1
import org.lfdecentralizedtrust.splice.store.UpdateHistory.BackfillingState
import org.scalatest.{Inspectors, LoneElement}
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}

import java.io.File
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.sys.process.ProcessLogger
import scala.util.Try
import scala.util.control.NonFatal

/** Runs `scripts/scan-txlog/scan_txlog.py`, to make sure that we have no transactions that would break it.
  *
  * @param ignoredRootCreates some tests create contracts directly via ledger_api_extensions.commands.
  *                            This is a list of TemplateIds that are created in such a way,
  *                            which won't cause an error in the script.
  */
class UpdateHistorySanityCheckPlugin(
    ignoredRootCreates: Seq[Identifier],
    ignoredRootExercises: Seq[(Identifier, String)],
    skipAcsSnapshotChecks: Boolean,
    protected val loggerFactory: SuppressingLogger,
) extends EnvironmentSetupPlugin[SpliceConfig, SpliceEnvironment]
    with Matchers
    with Eventually
    with Inspectors
    with ScalaFuturesWithPatience
    with LoneElement {

  override def beforeEnvironmentCreated(config: SpliceConfig): SpliceConfig = {
    updateAllScanAppConfigs_(config => config.copy(enableForcedAcsSnapshots = true))(
      super.beforeEnvironmentCreated(config)
    )
  }

  override def beforeEnvironmentDestroyed(
      config: SpliceConfig,
      environment: SpliceTestConsoleEnvironment,
  ): Unit = {
    TraceContext.withNewTraceContext("beforeEnvironmentDestroyed") { implicit tc =>
      // A scan might not be initialized if the test uses `manualStart` and it wasn't ever started.
      val initializedScans = environment.scans.local.filter(scan => scan.is_initialized)
      logger.debug(s"Checking update histories for ${initializedScans.map(_.name)}")

      TriggerTestUtil
        .setTriggersWithin(
          triggersToPauseAtStart = initializedScans.map(scan =>
            // prevent races with the trigger when taking the forced manual snapshot
            scan.automation.trigger[AcsSnapshotTrigger]
          ),
          triggersToResumeAtStart = Seq(),
        ) {
          // This flag should have the same value on all scans
          if (initializedScans.exists(_.config.updateHistoryBackfillEnabled)) {
            initializedScans.foreach(waitUntilBackfillingComplete)
            val (founders, others) = initializedScans.partition(_.config.isFirstSv)
            val founder = founders.loneElement
            val dsoRules =
              DsoRules.fromJson(founder.getDsoInfo().dsoRules.contract.payload.noSpaces)
            val (scansInDsoRules, scansNotInDsoRules) = others.partition { otherScan =>
              val svPartyId = otherScan.getDsoInfo().svPartyId
              dsoRules.svs.containsKey(svPartyId)
            }
            scansNotInDsoRules.foreach { notInDso =>
              // the SV will not see transactions to the DSO, but will still see transactions involving itself,
              // so comparison of history & snapshots will be broken if something like this happens:
              //  - U1: DSO-only
              //  - U2: Involves SV
              // founder sees U1, U2 but otherScan only U2
              logger.info(
                s"The SV party of Scan ${notInDso.name} (partyId=${notInDso.getDsoInfo().svPartyId}) is not in DsoRules. Ignoring."
              )
            }
            compareHistories(founder, scansInDsoRules)
            if (!skipAcsSnapshotChecks) {
              compareSnapshots(founder, scansInDsoRules)
            }
            initializedScans.foreach(checkScanTxLogScript)
          } else {
            // Just call the /updates endpoint, make sure whatever happened in the test doesn't blow it up,
            // and that pagination works as intended.
            // Without backfilling, history only works on the founding SV
            initializedScans.filter(_.config.isFirstSv).foreach(checkScanTxLogScript)
          }
        }
    }
  }

  @tailrec
  private def paginateHistory(
      scan: ScanAppBackendReference,
      after: Option[(Long, String)],
      acc: Chain[UpdateHistoryItemV2],
  ): Chain[UpdateHistoryItemV2] = {
    val result = scan.getUpdateHistory(10, after, encoding = CompactJson)
    val newAcc = acc ++ Chain.fromSeq(result)
    result.lastOption match {
      case None => acc // done
      case Some(members.UpdateHistoryTransactionV2(last)) =>
        paginateHistory(
          scan,
          Some((last.migrationId, last.recordTime)),
          newAcc,
        )
      case Some(members.UpdateHistoryReassignment(last)) =>
        last.event match {
          case reassignmentMembers.UpdateHistoryAssignment(event) =>
            paginateHistory(
              scan,
              Some((event.migrationId, last.recordTime)),
              newAcc,
            )
          case reassignmentMembers.UpdateHistoryUnassignment(event) =>
            paginateHistory(
              scan,
              Some((event.migrationId, last.recordTime)),
              newAcc,
            )
        }
    }
  }

  private def compareHistories(
      founder: ScanAppBackendReference,
      others: Seq[ScanAppBackendReference],
  ): Unit = {
    if (others.nonEmpty) {
      val founderHistory = paginateHistory(founder, None, Chain.empty).toVector
      forAll(others) { otherScan =>
        withClue(s"Comparing ${otherScan.name} to ${founder.name}") {
          val otherScanHistory = paginateHistory(otherScan, None, Chain.empty).toVector
          // One of them might be more advanced than the other.
          // That's fine, we mostly want to check that backfilling works as expected.
          val minSize = Math.min(founderHistory.size, otherScanHistory.size)
          val otherComparable = otherScanHistory
            .take(minSize)
          val founderComparable = founderHistory
            .take(minSize)
          val different = otherComparable
            .zip(founderComparable)
            .collect {
              case (otherItem, founderItem) if founderItem != otherItem =>
                otherItem -> founderItem
            }
          different should be(empty)
        }
      }
    }
  }

  private def checkScanTxLogScript(scan: ScanAppBackendReference)(implicit tc: TraceContext) = {
    val snapshotRecordTime = scan.forceAcsSnapshotNow()
    val amuletRules = scan.getAmuletRules()
    val amuletIncludesFees: Boolean =
      amuletRules.contract.payload.configSchedule.initialValue.packageConfig.amulet
        .split("\\.")
        .toList match {
        case major :: minor :: patch :: _ =>
          major.toInt == 0 && minor.toInt == 1 && patch.toInt <= 13
        case _ =>
          throw new IllegalArgumentException(
            s"Amulet package version is ${amuletRules.contract.payload.configSchedule.initialValue.packageConfig.amulet}, which is not x.y.z"
          )
      }
    // some tests have temporary participants, so the request won't always manage to resolve package support
    val compareBalancesWithTotalSupply = loggerFactory.suppressWarningsAndErrors(
      Try(scan.lookupInstrument("Amulet")).toOption.flatten.flatMap(_.totalSupply).isDefined
    )

    val readLines = mutable.Buffer[String]()
    val errorProcessor = ProcessLogger(line => readLines.append(line))
    val csvTempFile = File.createTempFile("scan_txlog", ".csv")
    // The script fails if the file already exists so delete it here.
    csvTempFile.delete()
    try {
      scala.sys.process
        .Process(
          Seq(
            "python",
            "scripts/scan-txlog/scan_txlog.py",
            scan.httpClientConfig.url.toString(),
            "--loglevel",
            "DEBUG",
            "--report-output",
            csvTempFile.toString,
            "--stop-at-record-time",
            snapshotRecordTime.toInstant.toString,
            "--compare-acs-with-snapshot",
            snapshotRecordTime.toInstant.toString,
          ) ++ Option
            .when(compareBalancesWithTotalSupply && !amuletIncludesFees)(
              "--compare-balances-with-total-supply"
            )
            .toList ++ ignoredRootCreates.flatMap { templateId =>
            Seq("--ignore-root-create", QualifiedName(templateId).toString)
          } ++ ignoredRootExercises.flatMap { case (templateId, choice) =>
            Seq("--ignore-root-exercise", s"${QualifiedName(templateId).toString}:$choice")
          }
        )
        .!(errorProcessor)
    } catch {
      case NonFatal(ex) =>
        logger.error("Failed to run scan_txlog.py. Dumping output.", ex)
        readLines.foreach(logger.error(_))
        throw new RuntimeException("scan_txlog.py failed.", ex)
    }

    withClue(readLines) {
      val lines = readLines.filter { log =>
        log.contains("ERROR:") || log.contains("WARNING:")
      }
      if (lines.nonEmpty) {
        val message = s"${this.getClass} contains errors: $lines, exiting test."
        logger.error(message)
        System.err.println(message)
        sys.exit(1)
      }
      lines should be(empty)
      forExactly(1, readLines) { line =>
        line should include("Reached end of stream")
      }
    }
  }

  private def waitUntilBackfillingComplete(
      scan: ScanAppBackendReference
  )(implicit tc: TraceContext): Unit = {
    if (scan.config.updateHistoryBackfillEnabled) {
      // Backfilling is initialized by ScanHistoryBackfillingTrigger, which should take 1-2 trigger invocations
      // to complete.
      // Most integration tests use config transforms to reduce the polling interval to 1sec,
      // but some tests might not use the transforms and end up with the default long polling interval.
      val estimatedTimeUntilBackfillingComplete =
        2 * scan.config.automation.pollingInterval.underlying + 5.seconds

      if (estimatedTimeUntilBackfillingComplete > 30.seconds) {
        logger.warn(
          s"Scan ${scan.name} has a long polling interval of ${scan.config.automation.pollingInterval.underlying}. " +
            "Please disable UpdateHistorySanityCheckPlugin for this test or reduce the polling interval to avoid long waits."
        )
      }

      withClue(s"Waiting for backfilling to complete on ${scan.name}") {
        val patienceConfigForBackfillingInit: PatienceConfig =
          PatienceConfig(
            timeout = estimatedTimeUntilBackfillingComplete,
            interval = Span(100, Millis),
          )
        eventually {
          scan.automation.updateHistory
            .getBackfillingState()
            .futureValue should be(BackfillingState.Complete)
        }(
          patienceConfigForBackfillingInit,
          implicitly[org.scalatest.enablers.Retrying[org.scalatest.Assertion]],
          implicitly[org.scalactic.source.Position],
        )
      }
    } else {
      logger.debug("Backfilling is disabled, skipping wait.")
    }
  }

  private def compareSnapshots(
      founder: ScanAppBackendReference,
      others: Seq[ScanAppBackendReference],
  ) = {
    if (others.nonEmpty) {
      val founderSnapshots = getAllSnapshots(founder, CantonTimestamp.MaxValue, Nil)
      forAll(others) { otherScan =>
        withClue(s"Comparing ${otherScan.name} to ${founder.name}") {
          val otherScanSnapshots = getAllSnapshots(otherScan, CantonTimestamp.MaxValue, Nil)
          // One of them might have more snapshots than the other.
          val minSize = Math.min(founderSnapshots.size, otherScanSnapshots.size)
          val otherComparable = otherScanSnapshots.take(minSize).map(toComparableSnapshot)
          val founderComparable = founderSnapshots.take(minSize).map(toComparableSnapshot)
          val different = otherComparable.zipWithIndex.collect {
            case (otherItem, idx) if founderComparable(idx) != otherItem =>
              (idx, otherItem, founderComparable(idx))
          }
          different should be(empty)
        }
      }
    }
  }

  private def toComparableSnapshot(acsResponse: AcsResponse) = {
    acsResponse.copy(createdEvents =
      acsResponse.createdEvents.map(_.copy(eventId = "different across nodes"))
    )
  }

  private def getAllSnapshots(
      scan: ScanAppBackendReference,
      before: CantonTimestamp,
      acc: List[AcsResponse],
  ): List[AcsResponse] = {
    val acsSnapshotPeriodHours = scanStorageConfigV1.dbAcsSnapshotPeriodHours
    val migrationId = scan.config.domainMigrationId
    scan.getDateOfMostRecentSnapshotBefore(before, migrationId) match {
      case Some(snapshotDate) =>
        val snapshot = scan
          .getAcsSnapshotAt(
            CantonTimestamp.assertFromInstant(snapshotDate.toInstant),
            migrationId,
            pageSize = 1000,
          )
          .getOrElse(throw new IllegalStateException("Snapshot must exist by this point"))
        getAllSnapshots(
          scan,
          CantonTimestamp.assertFromInstant(
            // +1 second because it's < date, instead of <= date
            snapshotDate.minusHours(acsSnapshotPeriodHours.toLong).plusSeconds(1L).toInstant
          ),
          snapshot :: acc,
        )
      case None =>
        acc
    }
  }
}
