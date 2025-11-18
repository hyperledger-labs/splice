package org.lfdecentralizedtrust.splice.performance

import com.digitalasset.canton.LogReporter
import com.typesafe.config.{Config, ConfigFactory}
import org.lfdecentralizedtrust.splice.performance.tests.DbSvDsoStoreIngestionPerformanceTest
import org.scalatest.{Args, Suite}

object PerformanceRunner extends App {

  // Set up logging: only WARN and ERROR to stdout
//  private val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
  // (Assume logging config is handled via HOCON or logback config file)

  // Parse CLI arguments
  case class CliArgs(command: String, testName: String, configPath: String)
  def parseArgs(args: Array[String]): CliArgs = {
    // Simple parser for: splice-perf run <test-name> -C <config>
    if (args.length < 4 || args(0) != "run" || args(2) != "-C")
      throw new IllegalArgumentException("Usage: splice-perf run <test-name> -C <config>")
    CliArgs(args(0), args(1), args(3))
  }

  val cliArgs = parseArgs(args)
  val config: Config = ConfigFactory.parseFile(new java.io.File(cliArgs.configPath)).resolve()
  val allTests: Map[String, () => Suite] = Map(
    "DbSvDsoStore" -> (() => new DbSvDsoStoreIngestionPerformanceTest())
  )

  // Globbing support for test names
  def getTestNames(pattern: String) = {
    if (pattern == "*") allTests.toSeq
    else allTests.filter(_._1.matches(pattern)).toSeq
  }

  val testsToRun = getTestNames(cliArgs.testName)

  // Run tests and report summary
  testsToRun.foreach { case (testName, testBuilder) =>
    val test = testBuilder()
    try {
      test.run(None, Args(reporter = new LogReporter()))
      println(s"Test '$testName' PASSED")
    } catch {
      case ex: Throwable =>
        println(s"Test '$testName' FAILED: ${ex.getMessage}")
    }
  }
}
