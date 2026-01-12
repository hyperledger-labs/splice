package org.lfdecentralizedtrust.splice.performance

import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.performance.tests.{
  ScanStoreIngestionPerformanceTest,
  StoreIngestionPerformanceTest,
  SvDsoStoreIngestionPerformanceTest,
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

    val allTests: Map[String, () => StoreIngestionPerformanceTest] = Map(
      "DbSvDsoStore" -> (() =>
        SvDsoStoreIngestionPerformanceTest.tryCreate(updateHistoryDumpPath, config, loggerFactory)
      ),
      "DbScanStore" -> (() =>
        ScanStoreIngestionPerformanceTest.tryCreate(updateHistoryDumpPath, config, loggerFactory)
      ),
    )

    val selectedTests: Map[String, () => StoreIngestionPerformanceTest] = {
      val regex = testNames.replace("*", ".*").r
      allTests.filter { case (name, _) => regex.matches(name) }
    }

    selectedTests.foreach { case (testName, testBuilder) =>
      try {
        val test = testBuilder()
        Await.result(test.run(), 1.hour) // surely 1h is enough... right?
        logger.info(s"Test '$testName' PASSED")
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Test '$testName' FAILED: ${ex.getMessage}")
      }
    }
  }

}
