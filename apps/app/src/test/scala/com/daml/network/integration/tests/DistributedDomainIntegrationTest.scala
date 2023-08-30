package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.{SvTestUtil, WalletTestUtil}
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.Port
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.networking.Endpoint
import com.digitalasset.canton.sequencing.GrpcSequencerConnection

import scala.jdk.OptionConverters.*

class DistributedDomainIntegrationTest
    extends CNNodeIntegrationTest
    with SvTestUtil
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withManualStart

  private val globalDomain = DomainAlias.tryCreate("global")

  "SV onboarding on distributed domain" in { implicit env =>
    initSvc()
    clue("Sequencers are initialized") {
      sv1Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv2Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv3Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv4Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    }

    clue("SV participants are connected to their own sequencers") {
      inside(
        sv1Backend.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) { case Seq(GrpcSequencerConnection(defaultSequencerEndpoint, _, _, _)) =>
        defaultSequencerEndpoint shouldBe NonEmpty
          .mk(Seq, Endpoint("localhost", Port.tryCreate(5008)))
          .toVector
      }
      inside(
        sv2Backend.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) {
        case Seq(
              GrpcSequencerConnection(defaultSequencerEndpoint, _, _, _),
              GrpcSequencerConnection(localSequencerEndpoint, _, _, _),
            ) =>
          defaultSequencerEndpoint shouldBe NonEmpty
            .mk(Seq, Endpoint("localhost", Port.tryCreate(5008)))
            .toVector
          localSequencerEndpoint shouldBe NonEmpty
            .mk(Seq, Endpoint("localhost", Port.tryCreate(5608)))
            .toVector
      }
      inside(
        sv3Backend.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) {
        case Seq(
              GrpcSequencerConnection(defaultSequencerEndpoint, _, _, _),
              GrpcSequencerConnection(localSequencerEndpoint, _, _, _),
            ) =>
          defaultSequencerEndpoint shouldBe NonEmpty
            .mk(Seq, Endpoint("localhost", Port.tryCreate(5008)))
            .toVector
          localSequencerEndpoint shouldBe NonEmpty
            .mk(Seq, Endpoint("localhost", Port.tryCreate(5708)))
            .toVector
      }
      inside(
        sv4Backend.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) {
        case Seq(
              GrpcSequencerConnection(defaultSequencerEndpoint, _, _, _),
              GrpcSequencerConnection(localSequencerEndpoint, _, _, _),
            ) =>
          defaultSequencerEndpoint shouldBe NonEmpty
            .mk(Seq, Endpoint("localhost", Port.tryCreate(5008)))
            .toVector
          localSequencerEndpoint shouldBe NonEmpty
            .mk(Seq, Endpoint("localhost", Port.tryCreate(5808)))
            .toVector
      }
    }

    clue("Mediator 1 is initialized") {
      sv1Backend.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv2Backend.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv3Backend.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv4Backend.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    }

    clue("SVC party is bootstrapped as a unionspace with SVs as owners") {
      val svcParty = sv1Backend.getSvcInfo().svcParty
      val domainId =
        sv1Backend.participantClient.domains.id_of(globalDomain)
      val unionspaces = sv1Backend.participantClient.topology.unionspaces
        .list(
          filterStore = domainId.filterString,
          filterNamespace = svcParty.uid.namespace.toProtoPrimitive,
        )
      inside(unionspaces) { case Seq(unionspace) =>
        unionspace.item.owners shouldBe Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)
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
      "SVs can change their coin price",
      Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend).foreach(_.updateCoinPriceVote(newPrice)),
    )(
      "SVs observe each others coin price changes",
      (_: Unit) =>
        forAll(Seq(sv1Backend, sv2Backend, sv3Backend, sv4Backend)) { sv =>
          val votes = sv.listCoinPriceVotes()
          votes should have size 4
          forAll(votes) { vote =>
            vote.payload.coinPrice.toScala.map(BigDecimal(_)) shouldBe Some(newPrice)
          }
        },
    )
  }

  "SVs can be onboarded a second time" in { implicit env =>
    initSvc()
  }
}
