package com.daml.network.integration.plugins

import com.daml.ledger.javaapi.data.Identifier
import com.daml.network.config.ConfigTransforms.updateAllScanAppConfigs_
import com.daml.network.config.SpliceConfig
import com.daml.network.console.ScanAppBackendReference
import com.daml.network.environment.EnvironmentImpl
import com.daml.network.http.v0.definitions.UpdateHistoryItem.members
import com.daml.network.http.v0.definitions.UpdateHistoryReassignment.Event.members as reassignmentMembers
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.scan.automation.AcsSnapshotTrigger
import com.daml.network.util.QualifiedName
import com.digitalasset.canton.ScalaFuturesWithPatience
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.Inspectors
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Span}

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.*
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

/** Runs `scripts/scan-txlog/scan_txlog.py`, to make sure that we have no transactions that would break it.
  *
  * @param ignoredRootCreates some tests create contracts directly via ledger_api_extensions.commands.
  *                            This is a list of TemplateIds that are created in such a way,
  *                            which won't cause an error in the script.
  */
class UpdateHistorySanityCheckPlugin(
    scanName: String,
    ignoredRootCreates: Seq[Identifier],
    ignoredRootExercises: Seq[(Identifier, String)],
    protected val loggerFactory: NamedLoggerFactory,
) extends EnvironmentSetupPlugin[EnvironmentImpl, SpliceTestConsoleEnvironment]
    with Matchers
    with Eventually
    with Inspectors
    with ScalaFuturesWithPatience {

  override def beforeEnvironmentCreated(config: SpliceConfig): SpliceConfig = {
    updateAllScanAppConfigs_(config => config.copy(enableForcedAcsSnapshots = true))(
      super.beforeEnvironmentCreated(config)
    )
  }

  override def beforeEnvironmentDestroyed(
      config: SpliceConfig,
      environment: SpliceTestConsoleEnvironment,
  ): Unit = {
    // TODO(#14270): actually, we should be able to run this against any scan app, not just SV1
    // Only SV1 will work.
    // Also, it might not be initialized if the test uses `manualStart` and it wasn't ever started.
    environment.scans.local.find(scan => scan.name == scanName && scan.is_initialized).foreach {
      scan =>
        // prevent races with the trigger when taking the forced manual snapshot
        scan.automation.trigger[AcsSnapshotTrigger].pause().futureValue

        val snapshotRecordTime = scan.forceAcsSnapshotNow()

        paginateHistory(scan, None)

        val readLines = mutable.Buffer[String]()
        val errorProcessor = ProcessLogger(line => readLines.append(line))
        try {
          scala.sys.process
            .Process(
              Seq(
                "python",
                "scripts/scan-txlog/scan_txlog.py",
                scan.httpClientConfig.url.toString(),
                "--loglevel",
                "DEBUG",
                "--scan-balance-assertions",
                "--stop-at-record-time",
                snapshotRecordTime.toInstant.toString,
                "--compare-acs-with-snapshot",
                snapshotRecordTime.toInstant.toString,
              ) ++ ignoredRootCreates.flatMap { templateId =>
                Seq("--ignore-root-create", QualifiedName(templateId).toString)
              } ++ ignoredRootExercises.flatMap { case (templateId, choice) =>
                Seq("--ignore-root-exercise", s"${QualifiedName(templateId).toString}:$choice")
              }
            )
            .!(errorProcessor)
        } catch {
          case NonFatal(ex) =>
            logger.error("Failed to run scan_txlog.py. Dumping output.", ex)(TraceContext.empty)
            readLines.foreach(logger.error(_)(TraceContext.empty))
            throw new RuntimeException("scan_txlog.py failed.", ex)
        }

        readLines.filter { log =>
          log.contains("ERROR:") || log.contains("WARNING:")
        } should be(empty)
        forExactly(1, readLines) { line =>
          line should include("Reached end of stream")
        }

        scan.automation.trigger[AcsSnapshotTrigger].resume()
    }
  }

  // Just call the /updates endpoint, make sure whatever happened in the test doesn't blow it up,
  // and that pagination works as intended.
  @tailrec
  private def paginateHistory(
      scan: ScanAppBackendReference,
      after: Option[(Long, String)],
  ): Unit = {
    val result = scan.getUpdateHistory(10, after, false)
    result.lastOption match {
      case None => () // done
      case Some(members.UpdateHistoryTransaction(last)) =>
        paginateHistory(scan, Some((last.migrationId, last.recordTime)))
      case Some(members.UpdateHistoryReassignment(last)) =>
        last.event match {
          case reassignmentMembers.UpdateHistoryAssignment(event) =>
            paginateHistory(scan, Some((event.migrationId, last.recordTime)))
          case reassignmentMembers.UpdateHistoryUnassignment(event) =>
            paginateHistory(scan, Some((event.migrationId, last.recordTime)))
        }
    }
  }

  // TODO(#14270): use this before running the scan_txlog.py script against a joining SV
  def waitUntilBackfillingComplete(
      scan: ScanAppBackendReference
  ): Unit = {
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
      )(TraceContext.empty)
    }

    withClue(s"Waiting for backfilling to complete on ${scan.name}") {
      val patienceConfigForBackfillingInit: PatienceConfig =
        PatienceConfig(
          timeout = estimatedTimeUntilBackfillingComplete,
          interval = Span(100, Millis),
        )
      eventually {
        scan.automation.store.updateHistory
          .getBackfillingState()(TraceContext.empty)
          .futureValue
          .exists(_.complete) should be(true)
      }(
        patienceConfigForBackfillingInit,
        implicitly[org.scalatest.enablers.Retrying[org.scalatest.Assertion]],
        implicitly[org.scalactic.source.Position],
      )
    }
  }
}
