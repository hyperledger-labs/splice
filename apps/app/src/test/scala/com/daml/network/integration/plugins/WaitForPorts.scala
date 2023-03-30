package com.daml.network.integration.plugins

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.integration.EnvironmentSetupPlugin
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.tests.CNNodeTests
import com.daml.network.config.CNNodeConfig
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName

import scala.concurrent.duration.*
import scala.util.Try
import sys.process.*
import com.digitalasset.canton.config.RequireTypes

/** A plugin that waits for a set of ports to become available. By default, waits for all ports
  * relevent to the app backends defined in the environment configuration.
  *
  * One problem this plugin solves is when a selenium webdriver from one test happens to use a port
  * that will be required by the following test, but leaves the port in a TIME_WAIT state.
  */
case class WaitForPorts(extraPortsToWaitFor: Seq[(String, Int)])
    extends EnvironmentSetupPlugin[CNNodeEnvironmentImpl, CNNodeTests.CNNodeTestConsoleEnvironment]
    with BaseTest {

  protected val timeout = 2.minutes

  override def beforeEnvironmentCreated(config: CNNodeConfig): CNNodeConfig = {
    config.validatorApps.foreach(validator =>
      waitForPort(validator._1, validator._2.adminApi.port.unwrap)
    )
    config.svcApp.foreach(svc =>
      waitForPort(InstanceName.tryCreate("SVC"), svc.adminApi.port.unwrap)
    )
    config.svApps.foreach(sv => waitForPort(sv._1, sv._2.adminApi.port.unwrap))
    config.walletAppBackends.foreach(wallet =>
      waitForPortAndHttpPort(wallet._1, wallet._2.adminApi.port)
    )
    config.splitwellApps.foreach(sw => waitForPort(sw._1, sw._2.adminApi.port.unwrap))
    config.directoryApp.foreach(directory =>
      waitForPort(InstanceName.tryCreate("Directory"), directory.adminApi.port.unwrap)
    )
    config.scanApp.foreach(scan =>
      waitForPort(InstanceName.tryCreate("Scan"), scan.adminApi.port.unwrap)
    )
    extraPortsToWaitFor.foreach(p => waitForPort(InstanceName.tryCreate(p._1), p._2))
    config
  }

  private def waitForPortAndHttpPort(
      instanceName: InstanceName,
      adminPort: RequireTypes.Port,
  ): Unit = {
    waitForPort(instanceName, adminPort.unwrap)
    waitForHttpPort(instanceName, adminPort.unwrap)
  }

  /** Bumps the port by 1000 before waiting for it, to match the http ports used by most of our apps
    * TODO(#2019) -- remove during grpc cleanup
    */
  private def waitForHttpPort(instanceName: InstanceName, adminPort: Int): Unit = {
    waitForPort(InstanceName.tryCreate(s"${instanceName}-http"), adminPort + 1000)
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
