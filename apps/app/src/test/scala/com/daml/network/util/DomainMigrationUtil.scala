package com.daml.network.util

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.metrics.api.noop.NoOpMetricsFactory
import com.daml.network.admin.api.client.{DamlGrpcClientMetrics, GrpcClientMetrics}
import com.daml.network.console.SvAppBackendReference
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.integration.tests.SpliceTests.TestCommon
import com.daml.network.util.DomainMigrationUtil.UpgradeSynchronizerNode
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.SuppressingLogger
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.scalatest.Assertion

import java.nio.file.{Path, Paths}
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.concurrent.duration.DurationInt
import scala.util.Using

trait DomainMigrationUtil extends BaseTest with TestCommon {
  override val loggerFactory: SuppressingLogger = SuppressingLogger(getClass)
  // This is all test code, don't wire up a metrics factory.
  val grpcClientMetrics: GrpcClientMetrics = new DamlGrpcClientMetrics(
    NoOpMetricsFactory,
    "testing",
  )

  def withClueAndLog[T](clueMessage: String)(fun: => T): T = withClue(clueMessage) {
    clue(clueMessage)(fun)
  }

  def checkMigrateDomainOnNodes(
      nodes: Seq[UpgradeSynchronizerNode],
      dsoParty: PartyId,
  )(implicit traceContext: TraceContext, ec: ExecutionContext): Unit = {

    val domainId = nodes.head.newParticipantConnection
      .getDomainId(
        nodes.head.newBackend.config.domains.global.alias
      )
      .futureValue

    forAllNodesAssert(nodes)("party hosting is replicated on the new global domain") {
      _.newParticipantConnection
        .getPartyToParticipant(
          domainId,
          dsoParty,
        )
    } {
      _.mapping.participantIds.size shouldBe 4
    }

    forAllNodesAssert(nodes)("all topology is synced") { node =>
      node.oldParticipantConnection
        .listAllTransactions(
          TopologyStoreId.DomainStore(domainId)
        )
        .map(node -> _)
    } { case (node, topologyState) =>
      node.newParticipantConnection
        .listAllTransactions(
          TopologyStoreId.DomainStore(domainId)
        )
        .futureValue
        .size shouldBe topologyState.size
    }

    forAllNodesAssert(nodes)("decentralized namespace is replicated on the new global domain") {
      _.newParticipantConnection
        .getDecentralizedNamespaceDefinition(
          domainId,
          dsoParty.uid.namespace,
        )
    } { oldMapping =>
      val decentralizedSynchronizerDecentralizedNamespaceDefinition =
        nodes.head.oldParticipantConnection
          .getDecentralizedNamespaceDefinition(domainId, dsoParty.uid.namespace)
          .futureValue
      oldMapping.mapping shouldBe decentralizedSynchronizerDecentralizedNamespaceDefinition.mapping
    }

    forAllNodesAssert(nodes)(
      "domain is unpaused on the new nodes"
    )(
      _.newParticipantConnection.getDomainParametersState(domainId)
    )(
      _.mapping.parameters.confirmationRequestsMaxRate should be > NonNegativeInt.zero
    )
  }

  def forAllNodesAssert[T](
      nodes: Seq[UpgradeSynchronizerNode]
  )(description: String)(
      getData: UpgradeSynchronizerNode => Future[T]
  )(assert: T => Assertion)(implicit ec: ExecutionContext): Unit = {
    withClueAndLog(description) {
      eventuallySucceeds(timeUntilSuccess = 1.minute) {
        nodes
          .parTraverse { node =>
            getData(node)
          }
          .map(
            _.map {
              assert
            }.discard
          )
          .futureValue
      }
    }
  }

  def createUpgradeNode(
      sv: Int,
      oldBackend: SvAppBackendReference,
      newBackend: SvAppBackendReference,
      retryProvider: RetryProvider,
      apiLoggingConfig: ApiLoggingConfig,
      portRange: Int = 27,
      oldPortRange: Int = 5,
  )(implicit
      ec: ExecutionContextExecutor
  ): UpgradeSynchronizerNode = {
    val loggerFactoryWithKey = loggerFactory.append("updateNode", sv.toString)
    logger.debug(
      s"Creating upgradeSynchronizerNode for SV ${sv.toString} with participant port $portRange"
    )

    def newParticipantAdminConnection(range: Int) = {
      val port = Port.tryCreate(range * 1000 + sv * 100 + 2)
      new ParticipantAdminConnection(
        ClientConfig(port = port),
        apiLoggingConfig,
        loggerFactoryWithKey,
        grpcClientMetrics,
        retryProvider,
      )
    }

    UpgradeSynchronizerNode(
      newParticipantAdminConnection(portRange),
      newParticipantAdminConnection(oldPortRange),
      oldBackend,
      newBackend,
    )
  }

}

object DomainMigrationUtil {
  case class UpgradeSynchronizerNode(
      newParticipantConnection: ParticipantAdminConnection,
      oldParticipantConnection: ParticipantAdminConnection,
      oldBackend: SvAppBackendReference,
      newBackend: SvAppBackendReference,
  )

  implicit val upgradeSynchronizerNodeReleasable: Using.Releasable[UpgradeSynchronizerNode] =
    (resource: UpgradeSynchronizerNode) => {
      resource.newParticipantConnection.close()
      resource.oldParticipantConnection.close()
    }

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

}
