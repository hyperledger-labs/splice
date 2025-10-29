package org.lfdecentralizedtrust.splice.util

import cats.implicits.catsSyntaxParallelTraverse1
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.config.{ApiLoggingConfig, FullClientConfig}
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import org.lfdecentralizedtrust.splice.console.SvAppBackendReference
import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryProvider}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.util.DomainMigrationUtil.UpgradeSynchronizerNode
import org.scalatest.Assertion

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Using

trait DomainMigrationUtil extends BaseTest with TestCommon {
  def withClueAndLog[T](clueMessage: String)(fun: => T): T = withClue(clueMessage) {
    clue(clueMessage)(fun)
  }

  def checkMigrateDomainOnNodes(
      nodes: Seq[UpgradeSynchronizerNode],
      dsoParty: PartyId,
  )(implicit traceContext: TraceContext, ec: ExecutionContext): Unit = {

    // also ensures domain is connected
    val synchronizerId = eventuallySucceeds()(
      nodes.head.newParticipantConnection
        .getSynchronizerId(
          nodes.head.newBackend.config.domains.global.alias
        )
        .futureValue
    )

    forAllNodesAssert(nodes)("party hosting is replicated on the new global domain") {
      _.newParticipantConnection
        .getPartyToParticipant(
          synchronizerId,
          dsoParty,
          None,
        )
    } {
      _.mapping.participantIds.size shouldBe 4
    }

    forAllNodesAssert(nodes)("all topology is synced") { node =>
      node.oldParticipantConnection
        .listAllTransactions(
          TopologyStoreId.SynchronizerStore(synchronizerId)
        )
        .map(node -> _)
    } { case (node, topologyState) =>
      node.newParticipantConnection
        .listAllTransactions(
          TopologyStoreId.SynchronizerStore(synchronizerId)
        )
        .futureValue
        .size shouldBe topologyState.size
    }

    forAllNodesAssert(nodes)("decentralized namespace is replicated on the new global domain") {
      _.newParticipantConnection
        .getDecentralizedNamespaceDefinition(
          synchronizerId,
          dsoParty.uid.namespace,
        )
    } { oldMapping =>
      val decentralizedSynchronizerDecentralizedNamespaceDefinition =
        nodes.head.oldParticipantConnection
          .getDecentralizedNamespaceDefinition(synchronizerId, dsoParty.uid.namespace)
          .futureValue
      oldMapping.mapping shouldBe decentralizedSynchronizerDecentralizedNamespaceDefinition.mapping
    }

    forAllNodesAssert(nodes)(
      "domain is unpaused on the new nodes"
    )(
      _.newParticipantConnection.getSynchronizerParametersState(synchronizerId)
    ) { params =>
      params.mapping.parameters.confirmationRequestsMaxRate should be > NonNegativeInt.zero
      params.mapping.parameters.mediatorReactionTimeout should be > NonNegativeFiniteDuration.Zero
      params.mapping.parameters.confirmationResponseTimeout should be > NonNegativeFiniteDuration.Zero
    }
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
        FullClientConfig(port = port),
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
