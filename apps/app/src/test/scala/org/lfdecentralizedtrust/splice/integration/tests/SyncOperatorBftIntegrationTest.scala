// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.{HasExecutionContext, SequencerAlias, SynchronizerAlias}
import com.digitalasset.canton.admin.api.client.data.{
  SubmissionRequestAmplification,
  SynchronizerConnectionConfig,
}
import com.digitalasset.canton.config.{FullClientConfig, SequencerApiClientConfig}
import com.digitalasset.canton.config.RequireTypes.{NonNegativeInt, NonNegativeLong, Port}
import com.digitalasset.canton.synchronizer.mediator.RemoteMediatorConfig
import com.digitalasset.canton.synchronizer.sequencer.config.RemoteSequencerConfig
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import org.lfdecentralizedtrust.splice.codegen.java.splice.decentralizedsynchronizer.GovernanceParameters
import org.lfdecentralizedtrust.splice.config.Thresholds
import org.lfdecentralizedtrust.splice.console.{MediatorClientReference, SequencerClientReference}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{StandaloneCanton, SyncOperatorTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.config.ValidatorAppBackendConfig

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** Runs a dedicated synchronizer on four BFT nodes with one sync operator per node, and checks
  * that every node grants a member's purchased traffic and that the nodes agree on what it
  * consumes.
  */
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_31
class SyncOperatorBftIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with StandaloneCanton
    with SyncOperatorTestUtil
    with WalletTestUtil {

  override def dbsSuffix = "sync_operator_bft"

  // The nodes' databases, see standalone-sync-operator-bft.conf, and the stores of operators 2 to
  // 4, see sync-operator-bft-topology.conf.
  override def usesDbs: Seq[String] =
    (1 to 4).flatMap(n =>
      Seq(
        s"sequencer_${dbsSuffix}_$n",
        s"sequencer_driver_${dbsSuffix}_$n",
        s"mediator_${dbsSuffix}_$n",
      )
    ) ++ (2 to 4).map(n => s"splice_apps_${dbsSuffix}_$n") ++ super.usesDbs

  private val purchase = 2_000_000L
  private val synchronizerAlias = SynchronizerAlias.tryCreate("syncOperatorBft")

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .fromResources(
        Seq(
          "simple-topology-1sv.conf",
          "sync-operator-topology.conf",
          "sync-operator-bft-topology.conf",
        ),
        this.getClass.getSimpleName,
      )
      .withOnlyAliceValidatorConnectingToSplitwell
      .withStandardSetup
      // The operators can only start once their synchronizer is bootstrapped.
      .withManualStart

  "sync operators" should {

    "run a dedicated synchronizer on four BFT nodes" in { implicit env =>
      initDsoWithSv1Only()
      startAllSync(aliceValidatorBackend, splitwellValidatorBackend)
      withBootstrappedCanton(
        Seq(testResourcesPath / "standalone-sync-operator-bft.conf"),
        testResourcesPath / "standalone-sync-operator-bft-bootstrap.sc",
        "sync-operator-bft",
      ) {
        val nodes = (1 to 4).map(node)

        val synchronizerId = clue("the four nodes bootstrap the synchronizer") {
          // Each node reports the synchronizer it serves once the bootstrap script has run.
          eventually(3.minutes) {
            forAll(nodes)(_.mediator.health.status.successOption should not be empty)
            nodes
              .map(_.sequencer.health.status.successOption.map(_.synchronizerId))
              .distinct
              .loneElement
              .value
              .logical
          }
        }

        clue("the synchronizer runs on all four sequencers and mediators") {
          eventually(1.minute) {
            val sequencerState = nodes.head.sequencer.topology.sequencers
              .list(store = Some(TopologyStoreId.Synchronizer(synchronizerId)))
              .loneElement
              .item
            sequencerState.active.forgetNE should contain theSameElementsAs nodes.map(
              _.sequencer.id
            )
            // The bootstrap script raises it from the one that genesis sets.
            sequencerState.threshold shouldBe Thresholds.sequencerConnectionsSizeThreshold(
              nodes.size
            )
          }
          val mediatorState = nodes.head.sequencer.topology.mediators
            .list(Some(synchronizerId), group = Some(NonNegativeInt.zero))
            .loneElement
            .item
          mediatorState.active.forgetNE should contain theSameElementsAs nodes.map(_.mediator.id)
          mediatorState.threshold shouldBe Thresholds.mediatorDomainStateThreshold(nodes.size)
        }

        clue("the four sequencers order together") {
          eventually() {
            forAll(nodes)(
              _.sequencer.bft
                .get_ordering_topology()
                .sequencerIds should contain theSameElementsAs nodes.map(_.sequencer.id)
            )
          }
        }

        clue("every node runs traffic control with a zero base rate") {
          // Each operator checks this on its own node when it starts.
          eventually(1.minute) {
            forAll(nodes)(
              _.sequencer.topology.synchronizer_parameters
                .get_dynamic_synchronizer_parameters(synchronizerId)
                .trafficControl
                .map(_.maxBaseTrafficAmount) shouldBe Some(NonNegativeLong.zero)
            )
          }
        }

        val operators = syncOperatorBackend +: (2 to 4).map(n => syncop(s"syncOperator$n"))
        startAllSync(operators*)

        clue("the operators grant the mediators unlimited traffic") {
          eventually(1.minute) {
            forAll(nodes)(n =>
              extraTrafficLimit(n.mediator.id) shouldBe NonNegativeLong.maxValue.value
            )
          }
        }

        val operatorParty = syncOperatorBackend.appState.store.key.operatorParty
        val dsoParty = sv1Backend.getDsoInfo().dsoParty
        val dsoRules = sv1Backend.getDsoInfo().dsoRules
        clue("the DSO registers the synchronizer to the operator") {
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(dsoParty),
              readAs = Seq(dsoParty),
              commands = dsoRules.contractId
                .exerciseDsoRules_RegisterSynchronizer(
                  synchronizerId.toProtoPrimitive,
                  operatorParty.toProtoPrimitive,
                  new GovernanceParameters(java.math.BigDecimal.ONE.setScale(10)),
                )
                .commands
                .asScala
                .toSeq,
              userId = sv1Backend.config.ledgerApiUser,
            )
        }
        val registration = eventually() {
          sv1ScanBackend.lookupSynchronizerRegistration(synchronizerId.toProtoPrimitive).value
        }

        val participant = aliceValidatorBackend.participantClientWithAdminToken
        val member = participant.id
        // The operators only grant traffic to members the synchronizer knows. Joining by handshake
        // alone makes the participant one, and needs no traffic.
        clue("the participant joins the synchronizer") {
          participant.synchronizers.register_by_config(
            SynchronizerConnectionConfig.tryGrpc(
              synchronizerAlias,
              nodes.map(n =>
                n.sequencer.sequencerConnection.withAlias(
                  SequencerAlias.tryCreate(n.sequencer.name)
                )
              ),
              // As a validator connects to the global synchronizer.
              sequencerTrustThreshold = Thresholds.sequencerConnectionsSizeThreshold(nodes.size),
              sequencerLivenessMargin = Thresholds.sequencerConnectionsLivenessMargin(nodes.size),
              submissionRequestAmplification = SubmissionRequestAmplification(
                Thresholds.sequencerSubmissionRequestAmplification(nodes.size),
                ValidatorAppBackendConfig.DefaultSequencerRequestAmplificationPatience,
              ),
            )
          )
          eventually() {
            forAll(nodes)(
              _.sequencer.topology.synchronizer_trust_certificates
                .list(
                  store = Some(TopologyStoreId.Synchronizer(synchronizerId)),
                  filterUid = member.filterString,
                ) should not be empty
            )
          }
        }

        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(walletUsdToAmulet(200.0))

        // The participant's purchased and consumed traffic as each node reports it.
        def trafficOnEachNode() =
          nodes.map(
            _.sequencer.traffic_control
              .traffic_state_of_members(Seq(member))
              .trafficStates
              .get(member)
              .map(state => (state.extraTrafficPurchased.value, state.extraTrafficConsumed.value))
          )

        actAndCheck(1.minute)(
          "alice buys traffic for her participant on the synchronizer",
          buyTraffic(aliceParty, member, synchronizerId, Some(registration), dsoParty, purchase),
        )(
          "every node grants it",
          _ => trafficOnEachNode().map(_.map(_._1)) shouldBe Seq.fill(nodes.size)(Some(purchase)),
        )

        // Connecting vets Canton's admin workflows on the synchronizer and pushes the participant's
        // parties, which the purchased traffic pays for.
        clue("the participant connects to all four nodes with the traffic it bought") {
          participant.synchronizers.reconnect(synchronizerAlias) shouldBe true
        }

        clue("the nodes agree on the traffic the participant consumes") {
          eventually(1.minute) {
            val (purchased, consumed) = trafficOnEachNode().distinct.loneElement.value
            purchased shouldBe purchase
            consumed should be > 0L
          }
        }

        clue("stop apps manually to prevent errors from the synchronizer being force stopped") {
          stopAllAsync(operators*).futureValue
          participant.synchronizers.disconnect(synchronizerAlias)
        }
      }
    }
  }

  // A node of the synchronizer. Node n uses ports 29n07 to 29n10, see
  // standalone-sync-operator-bft.conf.
  private case class Node(sequencer: SequencerClientReference, mediator: MediatorClientReference)

  private def node(n: Int)(implicit env: SpliceTestConsoleEnvironment): Node = {
    val base = 29000 + n * 100
    Node(
      new SequencerClientReference(
        env,
        s"syncOperatorBftSequencer$n",
        RemoteSequencerConfig(
          adminApi = FullClientConfig(port = Port.tryCreate(base + 9)),
          publicApi = SequencerApiClientConfig("localhost", Port.tryCreate(base + 8)),
        ),
      ),
      new MediatorClientReference(
        env,
        s"syncOperatorBftMediator$n",
        RemoteMediatorConfig(adminApi = FullClientConfig(port = Port.tryCreate(base + 7))),
      ),
    )
  }
}
