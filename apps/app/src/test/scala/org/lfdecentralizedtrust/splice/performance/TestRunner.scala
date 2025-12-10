package org.lfdecentralizedtrust.splice.performance

import com.digitalasset.canton.LogReporter
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.typesafe.config.{Config, ConfigFactory}
import org.lfdecentralizedtrust.splice.performance.tests.{
  DbScanStoreIngestionPerformanceTest,
  DbSvDsoStoreIngestionPerformanceTest,
}
import org.scalatest.{Args, Suite}

import scala.annotation.unused
import scala.util.control.NonFatal

class TestRunner(testNames: String, configPath: String)(implicit
    val loggerFactory: NamedLoggerFactory,
    tc: TraceContext,
) extends Runnable
    with NamedLogging {

  def run(): Unit = {
    @unused
    val config: Config = ConfigFactory.parseFile(new java.io.File(configPath)).resolve()

    val allTests: Map[String, () => Suite] = Map(
      "DbSvDsoStore" -> (() => new DbSvDsoStoreIngestionPerformanceTest()),
      "DbScanStore" -> (() => new DbScanStoreIngestionPerformanceTest()),
    )

    val selectedTests: Map[String, () => Suite] = {
      val regex = testNames.replace("*", ".*").r
      allTests.filter { case (name, _) => regex.matches(name) }
    }

    selectedTests.foreach { case (testName, testBuilder) =>
      try {
        val test = testBuilder()
        test.run(None, Args(reporter = new LogReporter()))
        logger.info(s"Test '$testName' PASSED")
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Test '$testName' FAILED: ${ex.getMessage}")
      }
    }
  }

}
