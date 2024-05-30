package com.daml.network.integration.plugins

import com.daml.network.config.CNNodeConfig
import com.daml.network.console.ScanAppBackendReference
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions.UpdateHistoryItem.members
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.logging.NamedLoggerFactory

import scala.annotation.tailrec

class UpdateHistorySanityCheckPlugin(protected val loggerFactory: NamedLoggerFactory)
    extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] {

  override def beforeEnvironmentDestroyed(
      config: CNNodeConfig,
      environment: CNNodeTestConsoleEnvironment,
  ): Unit = {
    environment.scans.local.foreach { scan =>
      // It might not be initialized if the test uses `manualStart` and it wasn't ever started.
      if (scan.is_initialized) {
        paginateHistory(scan, None)
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
