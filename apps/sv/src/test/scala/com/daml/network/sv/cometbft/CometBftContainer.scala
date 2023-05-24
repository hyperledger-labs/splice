// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package com.daml.network.sv.cometbft

import com.daml.network.sv.cometbft.CometBftContainer.{ContainerType, Testing}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.utility.{DockerImageName, MountableFile}
import org.testcontainers.images.ImagePullPolicy

class CometBftContainer(nodeType: ContainerType = Testing) extends NamedLogging {

  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

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
    // Always expect that images are pulled on CI before running tests
    .withImagePullPolicy(NeverPullOnCIImagePolicy)
    .withLogConsumer(
      new Slf4jLogConsumer(loggerFactory.getLogger(getClass)).withPrefix(s"CometBftNode[$nodeType]")
    )

  def initialize(network: Option[Network] = None): Unit = {
    network.foreach(container.setNetwork)
    container.addExposedPort(defaultCometBftHttpPort)
    container.waitingFor(Wait.forHttp("/health").forPort(defaultCometBftHttpPort))
    val modifiedContainer = nodeType match {
      case CometBftContainer.Testing =>
        container.withCreateContainerCmdModifier { createContainerCmd =>
          createContainerCmd.withEntrypoint("testing-entrypoint.sh")
          ()
        }
      case CometBftContainer.SvNetwork(id) =>
        container.withCreateContainerCmdModifier { createContainerCmd =>
          // Set hostname so that containers are discoverable through the sv1, sv2...namess
          createContainerCmd.withHostName(id)
          ()
        }
        container.setCommand(
          "start"
        )
        container.addEnv("TMHOME", "/testconfig")
        container
          .withCopyFileToContainer(
            MountableFile.forClasspathResource(
              s"cometbft/$id"
            ),
            "/testconfig",
          )
    }
    logger.info(s"Starting CometBFT node $nodeType")(TraceContext.empty)
    modifiedContainer.start()
    logger.info(s"Started CometBFT node $nodeType. Address: $getIp:$getPort")(TraceContext.empty)
  }

  def shutdown(): Unit = {
    logger.info(s"Shutting down CometBFT node $nodeType")(TraceContext.empty)
    container.stop()
  }

  def getPort: Integer = container.getMappedPort(defaultCometBftHttpPort)
  def getIp: String = container.getContainerIpAddress

}

object CometBftContainer {
  sealed trait ContainerType

  /** Create a testing network as defined by the `testing-entrypoint` bundled in the image
    * It's based on the simple example from the canton-drivers repo
    */
  case object Testing extends ContainerType

  /** Start a network based on the predefined `cometbft/sv[1-4]` configurations
    * The nodes are configure to run sv1 as validator and sv2,sv3,sv4 as read only nodes
    * Each node corresponds to sv1-4 from the simple topology used for tests
    */
  case class SvNetwork(id: String) extends ContainerType
}
