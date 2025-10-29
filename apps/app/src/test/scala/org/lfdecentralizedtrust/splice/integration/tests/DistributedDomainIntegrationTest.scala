package org.lfdecentralizedtrust.splice.integration.tests

import cats.syntax.parallel.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.BracketSynchronous.bracket
import org.lfdecentralizedtrust.splice.util.{SvTestUtil, WalletTestUtil}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, Port, PositiveInt}
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.sequencing.{
  GrpcSequencerConnection,
  SequencerConnection,
  SubmissionRequestAmplification,
}
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.util.FutureInstances.*

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

class DistributedDomainIntegrationTest extends IntegrationTest with SvTestUtil with WalletTestUtil {

  // Changed to a non-default value (the default is 250ms) to see that we correctly modify it.
  val observationLatency = NonNegativeFiniteDuration.ofMillis(500)

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .unsafeWithSequencerAvailabilityDelay(NonNegativeFiniteDuration.ofSeconds(5))
      // We deliberately change amulet conversion rate votes quickly in this test
      .addConfigTransform((_, config) => ConfigTransforms.withNoVoteCooldown(config))
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllValidatorConfigs_(
          _.copy(
            timeTrackerObservationLatency = observationLatency
          )
        )(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllSvAppConfigs_(
          _.copy(
            timeTrackerObservationLatency = observationLatency
          )
        )(config)
      )
      .withManualStart

  private val decentralizedSynchronizer = SynchronizerAlias.tryCreate("global")

  "SV onboarding on distributed domain" in { implicit env =>
    initDso()
    clue("Sequencers are initialized") {
      sv1Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv2Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv3Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv4Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    }

    clue("SV participants are connected to all sequencers") {
      forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { sv =>
        clue(s"sv ${sv.name} is connected to all sequencers") {
          eventually(60.seconds) {
            val synchronizerConfig = sv.participantClient.synchronizers
              .config(decentralizedSynchronizer)
              .value
            synchronizerConfig.timeTracker.observationLatency shouldBe observationLatency
            val sequencerConnections = synchronizerConfig.sequencerConnections
            val connections: Seq[SequencerConnection] = sequencerConnections.connections.forgetNE
            sequencerConnections.submissionRequestAmplification shouldBe SubmissionRequestAmplification(
              PositiveInt.tryCreate(2),
              NonNegativeFiniteDuration.ofSeconds(10),
            )
            val endpoints = connections.map { s =>
              inside(s) { case GrpcSequencerConnection(endpoint, _, _, _, _) =>
                endpoint
              }
            }
            endpoints.toSet shouldBe Seq(5108, 5208, 5308, 5408)
              .map(port =>
                NonEmpty
                  .mk(Seq, Endpoint("localhost", Port.tryCreate(port)))
                  .toVector
              )
              .toSet
          }
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
      val synchronizerId =
        sv1Backend.participantClient.synchronizers.id_of(decentralizedSynchronizer)
      val decentralizedNamespaces = sv1Backend.participantClient.topology.decentralized_namespaces
        .list(
          store = TopologyStoreId.Synchronizer(synchronizerId),
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
      sv1Backend.participantClient.synchronizers.id_of(decentralizedSynchronizer)
    eventuallySucceeds() {
      val parameters = sv1Backend.participantClientWithAdminToken.topology.synchronizer_parameters
        .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
      parameters.confirmationRequestsMaxRate should be > NonNegativeInt.zero
      parameters.mediatorReactionTimeout should be > com.digitalasset.canton.config.NonNegativeFiniteDuration.Zero
      parameters.confirmationResponseTimeout should be > com.digitalasset.canton.config.NonNegativeFiniteDuration.Zero
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
            val parameters = sv.participantClientWithAdminToken.topology.synchronizer_parameters
              .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
            parameters.confirmationRequestsMaxRate shouldBe NonNegativeInt.zero
            parameters.mediatorReactionTimeout shouldBe com.digitalasset.canton.config.NonNegativeFiniteDuration.Zero
            parameters.confirmationResponseTimeout shouldBe com.digitalasset.canton.config.NonNegativeFiniteDuration.Zero
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
            val parameters = sv.participantClientWithAdminToken.topology.synchronizer_parameters
              .get_dynamic_synchronizer_parameters(decentralizedSynchronizerId)
            parameters.confirmationRequestsMaxRate should be > NonNegativeInt.zero
            parameters.mediatorReactionTimeout should be > com.digitalasset.canton.config.NonNegativeFiniteDuration.Zero
            parameters.confirmationResponseTimeout should be > com.digitalasset.canton.config.NonNegativeFiniteDuration.Zero
          },
      )
    }
  }
}
