package org.lfdecentralizedtrust.splice.integration.plugins

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.digitalasset.canton.metrics.MetricsReporterConfig
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName

import scala.concurrent.duration.*
import scala.util.Try
import sys.process.*

/** A plugin that waits for a set of ports to become available. By default, waits for all ports
  * relevent to the app backends defined in the environment configuration.
  *
  * One problem this plugin solves is when a selenium webdriver from one test happens to use a port
  * that will be required by the following test, but leaves the port in a TIME_WAIT state.
  */
case class WaitForPorts(extraPortsToWaitFor: Seq[(String, Int)])
    extends EnvironmentSetupPlugin[EnvironmentImpl, SpliceTests.SpliceTestConsoleEnvironment]
    with BaseTest {

  protected val timeout = 2.minutes

  override def beforeEnvironmentCreated(config: SpliceConfig): SpliceConfig = {
    config.validatorApps.foreach(validator =>
      waitForPort(validator._1, validator._2.adminApi.port.unwrap)
    )
    config.svApps.foreach(sv => waitForPort(sv._1, sv._2.adminApi.port.unwrap))
    config.splitwellApps.foreach(sw => waitForPort(sw._1, sw._2.adminApi.port.unwrap))
    config.scanApps.foreach(scan => waitForPort(scan._1, scan._2.adminApi.port.unwrap))
    extraPortsToWaitFor.foreach(p => waitForPort(InstanceName.tryCreate(p._1), p._2))
    // Wait long enough that the prometheus server can start.
    config.monitoring.metrics.reporters.foreach {
      case MetricsReporterConfig.Prometheus(_, port, _) =>
        waitForPort(InstanceName.tryCreate("prometheus"), port.unwrap)
      case _ =>
    }
    config
  }

  private def waitForPort(instanceName: InstanceName, port: Int): Unit = {
    Try(sys.env("CI")).foreach(_ =>
      BaseTest.eventually(timeout) {
        logger.debug(s"Waiting for port ${port} to be available (used by ${instanceName})...")
        val ss = "ss -natp".!!
        val regex = raw"\S+\s+\S+\s+\S+\s+\S*[^: ]*:(\d+).*".r
        val header = raw"State.*".r
        ss.lines.forEach { line =>
          line match {
            case header() =>
            case regex(actualPort) =>
              if (actualPort == port.toString) {
                val pid = ProcessHandle.current().pid();
                val msg =
                  s"Port ${port} in use (we are pid $pid). Relevant ss lines are:\n${line}"
                logger.debug(msg)
                if (line.startsWith("TIME-WAIT")) {
                  logger.debug(
                    s"Port ${port} in use, but in TIME_WAIT state. Ignored, in hope that the TIME_WAIT ports can be reused"
                  )
                } else {
                  fail(msg)
                }
              }
            case _ => sys.error(s"Unexpected ss output: $line")
          }
        }
        logger.debug(s"Done waiting for port ${port}")
      }
    )
  }

}
