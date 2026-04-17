package org.lfdecentralizedtrust.splice.performance

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.performance.tests.{
  ScanStoreIngestionPerformanceTest,
  StoreIngestionPerformanceTest,
  StoreReadPerformanceTest,
  SvDsoStoreIngestionPerformanceTest,
  UpdateHistoryIngestionPerformanceTest,
  UpdateHistoryReadPerformanceTest,
}

import java.nio.file.Path
import scala.concurrent.{Await, ExecutionContext}
import scala.util.control.NonFatal
import scala.concurrent.duration.DurationInt

class TestRunner(testNames: String, configPath: Path, updateHistoryDumpPath: Path)(implicit
    ec: ExecutionContext,
    actorSystem: ActorSystem,
    val loggerFactory: NamedLoggerFactory,
    tc: TraceContext,
) extends Runnable
    with NamedLogging {

  def run(): Unit = {
    val config: Config = ConfigFactory.parseFile(configPath.toFile).resolve()

    val ingestionTests: Map[String, () => StoreIngestionPerformanceTest] = Map(
      "DbSvDsoStore" -> (() =>
        SvDsoStoreIngestionPerformanceTest.tryCreate(updateHistoryDumpPath, config, loggerFactory)
      ),
      "DbScanStore" -> (() =>
        ScanStoreIngestionPerformanceTest
          .tryCreate(updateHistoryDumpPath, config, loggerFactory)
      ),
      "UpdateHistory" -> (() =>
        UpdateHistoryIngestionPerformanceTest.tryCreate(
          updateHistoryDumpPath,
          config,
          loggerFactory,
        )
      ),
    )

    val readTests: Map[String, () => StoreReadPerformanceTest] = Map(
      "UpdateHistoryRead" -> (() =>
        UpdateHistoryReadPerformanceTest
          .tryCreate(updateHistoryDumpPath, config, loggerFactory)
      )
    )

    val regex = testNames.replace("*", ".*").r

    val selectedIngestionTests = ingestionTests.filter { case (name, _) => regex.matches(name) }
    val selectedReadTests = readTests.filter { case (name, _) => regex.matches(name) }

    def runTest(testName: String, runFuture: => scala.concurrent.Future[Unit]): Unit = {
      try {
        Await.result(runFuture, 1.hour)
        logger.info(s"Test '$testName' PASSED")
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Test '$testName' FAILED", ex)
          throw ex
      }
    }

    selectedIngestionTests.foreach { case (testName, testBuilder) =>
      runTest(testName, testBuilder().run())
    }

    selectedReadTests.foreach { case (testName, testBuilder) =>
      runTest(testName, testBuilder().run())
    }
  }

}
