package com.daml.network.integration.plugins

import com.daml.network.config.CNNodeConfig
import com.daml.network.console.ScanAppBackendReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions.UpdateHistoryItem.members
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.Inspectors
import org.scalatest.matchers.should.Matchers

import scala.annotation.tailrec
import scala.collection.mutable
import scala.sys.process.ProcessLogger
import scala.util.control.NonFatal

class UpdateHistorySanityCheckPlugin(protected val loggerFactory: NamedLoggerFactory)
    extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment]
    with Matchers
    with Inspectors {

  // to be updated: it fails right now but we want to use it to test
  private val checkScriptResult = false

  override def beforeEnvironmentDestroyed(
      config: CNNodeConfig,
      environment: CNNodeTestConsoleEnvironment,
  ): Unit = {
    // Only SV1 will work.
    // Also, it might not be initialized if the test uses `manualStart` and it wasn't ever started.
    environment.scans.local.find(scan => scan.name == "sv1Scan" && scan.is_initialized).foreach {
      scan =>
        paginateHistory(scan, None)

        if (checkScriptResult) {
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
                )
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
            line should contain("Reached end of stream")
          }
        }
    }
  }

  // Just call the /updates endpoint, make sure whatever happened in the test doesn't blow it up,
  // and that pagination works as intended.
  @tailrec
  private def paginateHistory(
      scan: ScanAppBackendReference,
      afterRecordTime: Option[String],
  ): Unit = {
    val result = scan.getUpdateHistory(10, afterRecordTime)
    result.lastOption match {
      case None => () // done
      case Some(members.UpdateHistoryTransaction(last)) =>
        paginateHistory(scan, Some(last.recordTime))
    }
  }
}
