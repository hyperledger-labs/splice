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

class XNodeDistributedDomainIntegrationTest
    extends CNNodeIntegrationTest
    with SvTestUtil
    with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopologyXDistributedDomain(this.getClass.getSimpleName)
      .withPreSetup(preSetup =
        implicit env =>
          // TODO(#5488) Switch to default init once it no longer allocates the SVC party
          Seq(sv1, sv2, sv3, sv4).foreach { sv =>
            sv.participantClientWithAdminToken.ledger_api.users.create(
              id = sv.config.ledgerApiUser,
              actAs = Set.empty,
              readAs = Set.empty,
              primaryParty = None,
              participantAdmin = true,
            )
          }
      )
      .withAllocatedValidatorUsers()
      .withManualStart

  private val globalDomain = DomainAlias.tryCreate("global")

  // TODO(#5586) Remove custom init logic once apps can initialize without domain already being connected.
  def initSvcDistributedDomain()(implicit env: CNNodeTestConsoleEnvironment) = {
    startAllSync(sv1, sv1Scan, sv1Validator, svc)
    val domainConfig = sv1.participantClient.domains.config(sv1.config.domains.global.alias).value
    Seq(sv2, sv3, sv4).foreach(_.participantClient.domains.connect(domainConfig))
    startAllSync(
      sv2,
      sv2Scan,
      sv2Validator,
      sv3,
      sv3Validator,
      sv4,
      sv4Validator,
    )
    domainConfig
  }

  "SV onboarding on X nodes" in { implicit env =>
    val globalDomainConfig = initSvcDistributedDomain()
    clue("Sequencers are initialized") {
      sv1.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv2.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv3.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv4.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    }

    clue("SV participants are connected to their own sequencers") {
      inside(
        sv1.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) { case Seq(GrpcSequencerConnection(endpoints, _, _, _)) =>
        endpoints shouldBe NonEmpty.mk(Seq, Endpoint("127.0.0.1", Port.tryCreate(5008)))
      }
      inside(
        sv2.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) { case Seq(GrpcSequencerConnection(endpoints, _, _, _)) =>
        endpoints shouldBe NonEmpty.mk(Seq, Endpoint("127.0.0.1", Port.tryCreate(5608)))
      }
      inside(
        sv3.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) { case Seq(GrpcSequencerConnection(endpoints, _, _, _)) =>
        endpoints shouldBe NonEmpty.mk(Seq, Endpoint("127.0.0.1", Port.tryCreate(5708)))
      }
      inside(
        sv4.participantClient.domains
          .config(globalDomain)
          .value
          .sequencerConnections
          .connections
          .forgetNE
      ) { case Seq(GrpcSequencerConnection(endpoints, _, _, _)) =>
        endpoints shouldBe NonEmpty.mk(Seq, Endpoint("127.0.0.1", Port.tryCreate(5808)))
      }
    }

    clue("Mediator 1 is initialized") {
      sv1.sequencerNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv2.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv3.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
      sv4.mediatorNodeStatus() should matchPattern { case NodeStatus.Success(_) => }
    }

    aliceValidator.participantClient.domains.connect(globalDomainConfig)
    aliceValidator.startSync()

    // Check that things work for external validators
    clue("Alice can tap") {
      onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(1000)
    }

    // Check that SVs can all submit transactions through their own sequencers
    // and observe each other’s transactions.
    actAndCheck(
      "SVs can change their coin price",
      Seq(sv1, sv2, sv3, sv4).foreach(_.updateCoinPriceVote(42)),
    )
    (
      "SVs observe each others coin price changes",
      (_: Unit) =>
        forAll(Seq(sv1, sv2, sv3, sv4)) { sv =>
          val votes = sv.listCoinPriceVotes()
          votes should have size 4
          forAll(votes) { vote =>
            vote.payload.coinPrice shouldBe Some(42)
          }
        },
    )
  }

  "SVs can be onboarded a second time" in { implicit env =>
    initSvcDistributedDomain()
  }
}
