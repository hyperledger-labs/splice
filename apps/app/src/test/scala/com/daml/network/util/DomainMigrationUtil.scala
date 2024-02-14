package com.daml.network.util

import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.time.WallClock
import com.daml.network.console.SvAppBackendReference
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.DomainMigrationUtil.UpgradeDomainNode
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.BaseTest
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.digitalasset.canton.logging.SuppressingLogger
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContextExecutor
import scala.util.Using

trait DomainMigrationUtil extends BaseTest with CNNodeTestCommon {
  override val loggerFactory: SuppressingLogger = SuppressingLogger(getClass)

  def withClueAndLog[T](clueMessage: String)(fun: => T) = withClue(clueMessage) {
    clue(clueMessage)(fun)
  }

  def checkMigrateDomainOnNodes(
      nodes: Seq[UpgradeDomainNode]
  )(implicit env: CNNodeTestConsoleEnvironment, traceContext: TraceContext): Unit = {

    val domainId = nodes.head.newParticipantConnection
      .getDomainId(
        nodes.head.newBackend.config.domains.global.alias
      )
      .futureValue

    withClueAndLog("party hosting is replicated on the new global domain") {
      nodes.foreach { node =>
        eventuallySucceeds(timeUntilSuccess = 1.minute) {
          node.newParticipantConnection
            .getPartyToParticipant(
              domainId,
              svcParty,
            )
            .futureValue
            .mapping
            .participantIds
            .size shouldBe 4
        }
      }
    }
    withClueAndLog("all topology is synced") {
      nodes.foreach { node =>
        val topologyTransactionsInOldStore = {
          node.oldBackend.appState.participantAdminConnection
            .listAllTransactions(
              Some(TopologyStoreId.DomainStore(domainId))
            )
            .futureValue
        }
        eventuallySucceeds(timeUntilSuccess = 1.minute) {
          node.newParticipantConnection
            .listAllTransactions(
              Some(TopologyStoreId.DomainStore(domainId))
            )
            .futureValue
            .size == topologyTransactionsInOldStore.size
        }
      }
    }

    withClueAndLog("decentralized namespace is replicated on the new global domain") {
      val globalDomainDecentralizedNamespaceDefinition =
        sv1Backend.appState.participantAdminConnection
          .getDecentralizedNamespaceDefinition(domainId, svcParty.uid.namespace)
          .futureValue
      eventuallySucceeds(timeUntilSuccess = 1.minute) {
        nodes.foreach { node =>
          node.newParticipantConnection
            .getDecentralizedNamespaceDefinition(
              domainId,
              svcParty.uid.namespace,
            )
            .futureValue
            .mapping shouldBe globalDomainDecentralizedNamespaceDefinition.mapping
        }
      }
    }
    withClueAndLog("domain is unpaused on the new nodes") {
      eventuallySucceeds() {
        nodes.foreach(
          _.newParticipantConnection
            .getDomainParametersState(
              domainId
            )
            .futureValue
            .mapping
            .parameters
            .maxRatePerParticipant should be > NonNegativeInt.zero
        )
      }
    }
  }

  def createUpgradeNode(
      sv: Int,
      oldBackend: SvAppBackendReference,
      newBackend: SvAppBackendReference,
      retryProvider: RetryProvider,
      wallClock: WallClock,
      portRange: Int = 27,
  )(implicit
      ec: ExecutionContextExecutor
  ) = {
    val loggerFactoryWithKey = loggerFactory.append("updateNode", sv.toString)
    val port = Port.tryCreate(portRange * 1000 + sv * 100 + 2)
    logger.debug(s"Creating upgradeDomainNode for SV ${sv.toString} with participant port $port")
    UpgradeDomainNode(
      new ParticipantAdminConnection(
        ClientConfig(port = port),
        loggerFactoryWithKey,
        retryProvider,
        wallClock,
      ),
      oldBackend,
      newBackend,
    )
  }

}

object DomainMigrationUtil {
  case class UpgradeDomainNode(
      newParticipantConnection: ParticipantAdminConnection,
      oldBackend: SvAppBackendReference,
      newBackend: SvAppBackendReference,
  )

  implicit val upgradeDomainNodeReleasable: Using.Releasable[UpgradeDomainNode] =
    (resource: UpgradeDomainNode) => {
      resource.newParticipantConnection.close()
    }

  val testDumpDir: Path = Paths.get("apps/app/src/test/resources/dumps")

}
