package org.lfdecentralizedtrust.splice.integration.tests

import cats.syntax.parallel.*
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.BracketSynchronous.bracket
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.sequencing.GrpcSequencerConnection
import com.digitalasset.canton.util.FutureInstances.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

class DistributedDomainIntegrationTest extends IntegrationTest with SvTestUtil with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      .withManualStart

  private val decentralizedSynchronizer = DomainAlias.tryCreate("global")

  "SV onboarding on distributed domain" in { implicit env =>
    initDso()
    clue("Sequencers are initialized") {
      sv1Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv2Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv3Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv4Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    }

    clue("SV participants are connected to their own sequencers") {
      eventually() {
        inside(
          sv1Backend.participantClient.domains
            .config(decentralizedSynchronizer)
            .value
            .sequencerConnections
            .connections
            .forgetNE
        ) { case Seq(GrpcSequencerConnection(defaultSequencerEndpoint, _, _, _)) =>
          defaultSequencerEndpoint shouldBe NonEmpty
            .mk(Seq, Endpoint("localhost", Port.tryCreate(5108)))
            .toVector
        }
        inside(
          sv2Backend.participantClient.domains
            .config(decentralizedSynchronizer)
            .value
            .sequencerConnections
            .connections
            .forgetNE
        ) {
          case Seq(
                GrpcSequencerConnection(localSequencerEndpoint, _, _, _)
              ) =>
            localSequencerEndpoint shouldBe NonEmpty
              .mk(Seq, Endpoint("localhost", Port.tryCreate(5208)))
              .toVector
        }
        inside(
          sv3Backend.participantClient.domains
            .config(decentralizedSynchronizer)
            .value
            .sequencerConnections
            .connections
            .forgetNE
        ) {
          case Seq(
                GrpcSequencerConnection(localSequencerEndpoint, _, _, _)
              ) =>
            localSequencerEndpoint shouldBe NonEmpty
              .mk(Seq, Endpoint("localhost", Port.tryCreate(5308)))
              .toVector
        }
        inside(
          sv4Backend.participantClient.domains
            .config(decentralizedSynchronizer)
            .value
            .sequencerConnections
            .connections
            .forgetNE
        ) {
          case Seq(
                GrpcSequencerConnection(localSequencerEndpoint, _, _, _)
              ) =>
            localSequencerEndpoint shouldBe NonEmpty
              .mk(Seq, Endpoint("localhost", Port.tryCreate(5408)))
              .toVector
        }
      }
    }

    clue("Mediator 1 is initialized") {
      sv1Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv2Backend.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv3Backend.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv4Backend.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    }

    clue("DSO party is bootstrapped as a decentralized namespace with SVs as owners") {
      val dsoParty = sv1Backend.getDsoInfo().dsoParty
      val domainId =
        sv1Backend.participantClient.domains.id_of(decentralizedSynchronizer)
      val decentralizedNamespaces = sv1Backend.participantClient.topology.decentralized_namespaces
        .list(
          filterStore = domainId.filterString,
          filterNamespace = dsoParty.uid.namespace.toProtoPrimitive,
        )
      inside(decentralizedNamespaces) { case Seq(decentralizedNamespace) =>
        decentralizedNamespace.item.owners shouldBe Seq(
          sv1Backend,
          sv2Backend,
          sv3Backend,
          sv4Backend,
        )
          .map(_.participantClient.id.uid.namespace)
          .toSet
      }
    }

    aliceValidatorBackend.startSync()

    // Check that things work for external validators
    clue("Alice can tap") {
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(1000)
    }

    // Check that SVs can all submit transactions through their own sequencers
    // and observe each other’s transactions.
    val newPrice = BigDecimal(42)
    actAndCheck(
      "SVs can change their amulet price",
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach(_.updateAmuletPriceVote(newPrice)),
    )(
      "SVs observe each others amulet price changes",
      (_: Unit) =>
        forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { sv =>
          val votes = sv.listAmuletPriceVotes()
          votes should have size 4
          forAll(votes) { vote =>
            vote.payload.amuletPrice.toScala.map(BigDecimal(_)) shouldBe Some(newPrice)
          }
        },
    )
  }

  "SVs can be onboarded a second time" in { implicit env =>
    initDso()
  }

  "SVs can pause and unpause the domain via SV app API calls" in { implicit env =>
    implicit val ec: ExecutionContext = env.executionContext
    initDso()
    val decentralizedSynchronizerId =
      sv1Backend.participantClient.domains.id_of(decentralizedSynchronizer)
    eventuallySucceeds() {
      sv1Backend.participantClientWithAdminToken.topology.domain_parameters
        .get_dynamic_domain_parameters(decentralizedSynchronizerId)
        .confirmationRequestsMaxRate should be > NonNegativeInt.zero
    }

    bracket(
      (), {
        clue(
          s"un-pause decentralized synchronizer to not crash other tests"
        ) {
          Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).parTraverse { sv =>
            Future {
              sv.unpauseDecentralizedSynchronizer()
            }
          }.futureValue
        }
      },
    ) {
      actAndCheck(
        "SVs can pause the decentralizedSynchronizer",
        Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).parTraverse { sv =>
          Future {
            sv.pauseDecentralizedSynchronizer()
          }
        }.futureValue,
      )(
        "decentralizedSynchronizer is paused",
        _ =>
          forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { sv =>
            sv.participantClientWithAdminToken.topology.domain_parameters
              .get_dynamic_domain_parameters(decentralizedSynchronizerId)
              .confirmationRequestsMaxRate shouldBe NonNegativeInt.zero
          },
      )

      actAndCheck(
        "SVs can unpause the decentralizedSynchronizer",
        Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).parTraverse { sv =>
          Future {
            sv.unpauseDecentralizedSynchronizer()
          }
        }.futureValue,
      )(
        "decentralizedSynchronizer is un-paused",
        _ =>
          forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { sv =>
            sv.participantClientWithAdminToken.topology.domain_parameters
              .get_dynamic_domain_parameters(decentralizedSynchronizerId)
              .confirmationRequestsMaxRate should be > NonNegativeInt.zero
          },
      )
    }
  }
}
