package com.daml.network.sv.cometbft

import org.scalatest.{BeforeAndAfterAll, Suite}

trait CometBftContainerAround extends BeforeAndAfterAll {
  this: Suite =>

  private val container = new CometBftContainer(getClass.getSimpleName)
  lazy val connectionConfig: CometBftConnectionConfig = CometBftConnectionConfig(
    s"http://${container.getIp}:${container.getPort}"
  )

  override protected def beforeAll(): Unit = {
    super.beforeAll()
    container.initialize()
  }

  override protected def afterAll(): Unit = {
    super.afterAll()
    container.shutdown()
  }

}
