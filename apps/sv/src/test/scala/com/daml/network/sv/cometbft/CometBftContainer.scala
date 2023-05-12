// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package com.daml.network.sv.cometbft

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

class CometBftContainer {

  private val defaultCometBftHttpPort = 26657

  class CometBftTestContainer
      extends GenericContainer[CometBftTestContainer](
        DockerImageName.parse(
          "digitalasset-canton-enterprise-docker.jfrog.io/cometbft-canton-network:53d33e114a70b311f7b048f016243449635ca3ad"
        )
      )

  private val container = new CometBftTestContainer().withCreateContainerCmdModifier {
    createContainerCmd =>
      createContainerCmd.withEntrypoint("testing-entrypoint.sh")
      ()
  }

  def initialize(): Unit = {
    container.addExposedPort(defaultCometBftHttpPort)
    container.waitingFor(Wait.forHttp("/health").forPort(defaultCometBftHttpPort))
    container.start()
  }

  def shutdown(): Unit = {
    container.stop()
  }

  def getPort: Integer = container.getMappedPort(defaultCometBftHttpPort)
  def getIp: String = container.getContainerIpAddress

}
