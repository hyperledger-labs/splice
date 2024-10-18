// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.

package org.lfdecentralizedtrust.splice.sv.cometbft

import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftContainer.{ContainerType, SingleNode}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.containers.{GenericContainer, Network}
import org.testcontainers.images.{AbstractImagePullPolicy, ImageData}
import org.testcontainers.utility.{DockerImageName, MountableFile}

import java.io.{FileWriter, PrintWriter}

class CometBftContainer(testIdentifier: String, nodeType: ContainerType = SingleNode)
    extends NamedLogging {

  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  private val defaultCometBftHttpPort = 26657

  class CometBftTestContainer
      extends GenericContainer[CometBftTestContainer](
        DockerImageName.parse(
          sys.env("COMETBFT_DOCKER_IMAGE")
        )
      )

  /** If the we're in CI, the image should not be pulled as we expect all images to be present in CI.
    * If not we pull only if the image is not present.
    */
  case object NeverPullOnCIImagePolicy extends AbstractImagePullPolicy {

    override def shouldPull(imageName: DockerImageName): Boolean = {
      val isCI = sys.env.contains("CI")
      if (isCI) {
        false
      } else {
        super.shouldPull(imageName)
      }
    }

    override def shouldPullCached(
        imageName: DockerImageName,
        localImageData: ImageData,
    ): Boolean = false

  }

  private val logWriter = new PrintWriter(
    new FileWriter(s"${sys.env("LOGS_PATH")}/cometbft-$testIdentifier-$nodeType.log"),
    true,
  )

  private val container = new CometBftTestContainer()
    // Always expect that images are pulled on CI before running tests
    .withImagePullPolicy(NeverPullOnCIImagePolicy)
    .withLogConsumer(frame => {
      logWriter.print(frame.getUtf8String)
    })

  def initialize(network: Option[Network] = None): Unit = {
    network.foreach(container.setNetwork)
    container.addExposedPort(defaultCometBftHttpPort)
    container.waitingFor(Wait.forHttp("/health").forPort(defaultCometBftHttpPort))

    def cometbftNodeForSv(id: String) = {
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
        // Share the genesis file
        .withCopyFileToContainer(
          MountableFile.forClasspathResource(
            s"cometbft/genesis-single-validator.json"
          ),
          "/testconfig/config/genesis.json",
        )
    }

    val modifiedContainer = nodeType match {
      case CometBftContainer.SingleNode =>
        cometbftNodeForSv("sv1")
      case CometBftContainer.SvNetwork(id) =>
        cometbftNodeForSv(id)
    }
    logger.info(s"Starting CometBFT node $nodeType")(TraceContext.empty)
    modifiedContainer.start()
    logger.info(s"Started CometBFT node $nodeType. Address: $getIp:$getPort")(TraceContext.empty)
  }

  def shutdown(): Unit = {
    logger.info(s"Shutting down CometBFT node $nodeType")(TraceContext.empty)
    container.stop()
    logWriter.close()
  }

  def getPort: Integer = container.getMappedPort(defaultCometBftHttpPort)
  def getIp: String = container.getContainerIpAddress

}

object CometBftContainer {
  sealed trait ContainerType

  /** Create a testing network as defined by the `testing-entrypoint` bundled in the image
    * It's based on the simple example from the canton-drivers repo
    */
  case object SingleNode extends ContainerType

  /** Start a network based on the predefined `cometbft/sv[1-4]` configurations
    * The nodes are configure to run sv1 as validator and sv2,sv3,sv4 as read only nodes
    * Each node corresponds to sv1-4 from the simple topology used for tests
    */
  case class SvNetwork(id: String) extends ContainerType
}
