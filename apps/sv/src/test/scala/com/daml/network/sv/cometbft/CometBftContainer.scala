// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package com.daml.network.sv.cometbft

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.ImagePullPolicy
import org.testcontainers.utility.DockerImageName

class CometBftContainer {

  private val defaultCometBftHttpPort = 26657

  class CometBftTestContainer
      extends GenericContainer[CometBftTestContainer](
        DockerImageName.parse(
          sys.env("COMETBFT_DOCKER_IMAGE")
        )
      )

  case object NeverPullOnCIImagePolicy extends ImagePullPolicy {
    override def shouldPull(imageName: DockerImageName): Boolean =
      !sys.env.contains("CI")
  }

  private val container = new CometBftTestContainer()
    .withCreateContainerCmdModifier { createContainerCmd =>
      createContainerCmd.withEntrypoint("testing-entrypoint.sh")
      ()
    }
    // Always expect that images are pulled on CI before running tests
    .withImagePullPolicy(NeverPullOnCIImagePolicy)

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
