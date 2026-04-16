package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.digitalasset.canton.util.MonadUtil
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvStatusReport
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllSvAppConfigs_
import org.lfdecentralizedtrust.splice.console.{
  ScanAppBackendReference,
  SvAppBackendReference,
  ValidatorAppBackendReference,
}
import org.lfdecentralizedtrust.splice.environment.RetryFor
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.ParticipantPermission

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceDsoGovernance_0_1_24
class SvInitializationIntegrationTest extends SvIntegrationTestBase {

  override protected def runEventHistorySanityCheck: Boolean = false

  override def environmentDefinition: EnvironmentDefinition =
    super.environmentDefinition
      .addConfigTransforms((_, config) =>
        updateAllSvAppConfigs_ { c =>
          c.copy(onLedgerStatusReportInterval = NonNegativeFiniteDuration.ofSeconds(1))
        }(config)
      )

  "start and restart cleanly" in { implicit env =>
    initDso()
    sv1Backend.stop()
    sv1Backend.startSync()
  }

  "can start before its validator app" in { implicit env =>
    // we want this so we can have a clear init dependency validator app -> sv app
    clue("Starting sv1's SV app") {
      sv1Backend.startSync()
    }
    clue("Starting sv1's scan app") {
      // validators need this
      sv1ScanBackend.startSync()
    }
    clue("Starting sv1's validator app") {
      sv1ValidatorBackend.startSync()
    }
    clue("Starting joining SV's SV app") {
      sv2Backend.startSync()
    }
    clue("Starting joining SV's Scan app") {
      sv2ScanBackend.startSync() // without this, sv2&4's validator will fail.
    }
    clue("Starting joining SV's validator app") {
      sv2ValidatorBackend.startSync()
    }
  }

  "connect to all domains during initialization" in { implicit env =>
    initDso()
    sv4Backend.stop()

    val decentralizedSynchronizerId =
      inside(sv4Backend.participantClient.synchronizers.list_connected()) { case Seq(domain) =>
        domain.synchronizerId
      }

    clue("simulate the domain was left disconnected when error occur during party migration.") {
      sv4Backend.participantClient.synchronizers.disconnect_all()
      sv4Backend.participantClient.synchronizers
        .list_connected() shouldBe empty withClue "synchronizers"
    }

    clue("sv will connect to all domains during initialization.") {
      sv4Backend.startSync()
      inside(sv4Backend.participantClient.synchronizers.list_connected()) {
        case Seq(listConnectedDomainsResult) =>
          listConnectedDomainsResult.synchronizerId shouldBe decentralizedSynchronizerId
      }
    }
  }

  // A test to make debugging bootstrap problems easier
  "SV apps can start one by one" in { implicit env =>
    import env.executionContext
    clue("Starting DSO app and SV1 app") {
      startAllSync(sv1ScanBackend, sv1ValidatorBackend, sv1Backend)
    }

    def startSv(
        number: Int,
        sv: SvAppBackendReference,
        validator: ValidatorAppBackendReference,
        scanApp: ScanAppBackendReference,
    ) =
      clue(s"Starting SV$number app") {
        validator.start()
        sv.start()
        scanApp.start()
        validator.waitForInitialization()
        sv.waitForInitialization()
        scanApp.waitForInitialization()
      }

    startSv(2, sv2Backend, sv2ValidatorBackend, sv2ScanBackend)
    startSv(3, sv3Backend, sv3ValidatorBackend, sv3ScanBackend)
    // Increase the decentralized namespace threshold to 3 to require more than the candidate and sponsor to authorize the party to participant mapping. This ensures that the party to participant reconciliation loops work as expected.
    // do this by falsely adding the sequencer namespace to the decentralized namespace
    val sv1SequencerAdminConnection =
      sv1Backend.appState.localSynchronizerNodes.current.sequencerAdminConnection
    val sv1SequencerId = sv1SequencerAdminConnection.getSequencerId.futureValue
    val newDecentralizedNamespace = MonadUtil
      .parTraverseWithLimit(PositiveInt.tryCreate(4))(
        Seq(
          sv1SequencerAdminConnection,
          sv1Backend.appState.participantAdminConnection,
          sv2Backend.appState.participantAdminConnection,
        )
      ) { connection =>
        connection
          .ensureDecentralizedNamespaceDefinitionProposalAccepted(
            decentralizedSynchronizerId,
            dsoParty.uid.namespace,
            sv1SequencerId.uid.namespace,
            RetryFor.WaitingOnInitDependency,
          )
      }
      .futureValue
      .headOption
      .value
    newDecentralizedNamespace.mapping.threshold shouldBe PositiveInt.tryCreate(3)

    try {
      startSv(4, sv4Backend, sv4ValidatorBackend, sv4ScanBackend)

      def nodeStates = {
        val rulesAndState =
          sv1Backend.appState.dsoStore.getDsoRulesWithSvNodeStates().futureValue
        rulesAndState.svNodeStates.values
          .map(
            _.payload.state.synchronizerNodes
              .get(decentralizedSynchronizerId.toProtoPrimitive)
          )
      }

      clue("All SVs have reported their Scan URLs in DSO rules") {
        eventually() {
          nodeStates.flatMap(_.scan.toScala.map(_.publicUrl)) should contain theSameElementsAs Seq(
            "http://localhost:5012",
            "http://localhost:5112",
            "http://localhost:5212",
            "http://localhost:5312",
          )
        }
      }

      clue("physical synchronizers configs are set") {
        val allPhysicalConfigs = nodeStates.flatMap(_.physicalSynchronizers.toScala.value.asScala)
        allPhysicalConfigs.size shouldBe 4
        allPhysicalConfigs.map(_._1).toSeq.distinct.loneElement shouldBe java.lang.Long.valueOf(0)
        val configs = allPhysicalConfigs.map(_._2)
        configs.map(_.sequencer.toScala.value.url) should contain theSameElementsAs Seq(
          "http://localhost:5108",
          "http://localhost:5208",
          "http://localhost:5308",
          "http://localhost:5408",
        )
      }
      clue("backwards compatible synchronizers configs is set") {
        val allSequencerConfigs = nodeStates.map(_.sequencer.toScala.value)
        allSequencerConfigs.size shouldBe 4
        allSequencerConfigs.map(_.migrationId).toSeq.distinct.loneElement shouldBe java.lang.Long
          .valueOf(0)
        allSequencerConfigs.map(_.url) should contain theSameElementsAs Seq(
          "http://localhost:5108",
          "http://localhost:5208",
          "http://localhost:5308",
          "http://localhost:5408",
        )
      }
    } finally {
      // Remove the sequencer again, otherwise the logic for resetting the namespace to only contain
      // sv1 will fail.
      MonadUtil
        .parTraverseWithLimit(PositiveInt.tryCreate(4))(
          Seq(
            sv1Backend.appState.participantAdminConnection,
            sv2Backend.appState.participantAdminConnection,
            sv3Backend.appState.participantAdminConnection,
            sv4Backend.appState.participantAdminConnection,
          )
        ) { connection =>
          connection
            .ensureDecentralizedNamespaceDefinitionRemovalProposal(
              decentralizedSynchronizerId,
              dsoParty.uid.namespace,
              sv1SequencerId.uid.namespace,
              RetryFor.WaitingOnInitDependency,
            )
        }
        .futureValue
        .headOption
        .value

      // Ensure that it's actually gone to avoid races in the ResetDecentralizedNamespace plugin
      // where the topology is submitted to all but has not yet gone through
      eventually() {
        sv1Backend.participantClientWithAdminToken.topology.decentralized_namespaces
          .list(TopologyStoreId.Synchronizer(decentralizedSynchronizerId))
          .loneElement
          .item
          .owners
          .map(_.fingerprint)
          .forgetNE should not contain sv1SequencerId.fingerprint
      }
    }
  }

  "The DSO is bootstrapped correctly" in { implicit env =>
    initDso()
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getDsoInfo().svParty.toProtoPrimitive)
    }
    val svPartiesSet = svParties.toSet
    clue("The four SV apps are all SVs and there are no other SVs") {
      sv1Backend.getDsoInfo().dsoRules.payload.svs.keySet() should equal(svParties.toSet.asJava)
    }
    clue("initial open mining rounds are created") {
      eventually() {
        sv1Backend.listOpenMiningRounds() should have size 3 withClue "sv1 OpenMiningRounds"
        sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1 should have size 3 withClue "sv1 scan OpenMiningRounds"
        sv2ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1 should have size 3 withClue "sv2 scan OpenMiningRounds"
      }
    }
    clue("thresholds are set as expected") {
      eventually() {
        val participantAdminConnection = sv1Backend.appState.participantAdminConnection
        participantAdminConnection
          .getDecentralizedNamespaceDefinition(
            decentralizedSynchronizerId,
            dsoParty.uid.namespace,
            topologySnapshot = TopologySnapshot.Sequenced,
          )
          .futureValue
          .mapping
          .threshold shouldBe PositiveInt.tryCreate(3)
        participantAdminConnection
          .getSequencerSynchronizerState(
            decentralizedSynchronizerId,
            topologySnapshot = TopologySnapshot.Sequenced,
          )
          .futureValue
          .mapping
          .threshold shouldBe PositiveInt.tryCreate(2)
        participantAdminConnection
          .getMediatorSynchronizerState(
            decentralizedSynchronizerId,
            topologySnapshot = TopologySnapshot.Sequenced,
          )
          .futureValue
          .mapping
          .threshold shouldBe PositiveInt.tryCreate(2)
        participantAdminConnection
          .getPartyToParticipant(
            decentralizedSynchronizerId,
            dsoParty,
            topologySnapshot = TopologySnapshot.Sequenced,
          )
          .futureValue
          .mapping
          .threshold
          .value shouldBe 3
      }
    }
    clue("SV participants have submission rights on behalf of the DSO party") {
      eventually() {
        val participantAdminConnection = sv1Backend.appState.participantAdminConnection
        val dsoHostingParticipants = participantAdminConnection
          .getPartyToParticipant(
            decentralizedSynchronizerId,
            dsoParty,
            topologySnapshot = TopologySnapshot.Sequenced,
          )
          .futureValue
          .mapping
          .participants
        dsoHostingParticipants should have length 4
        dsoHostingParticipants.foreach(_.permission shouldBe ParticipantPermission.Submission)
      }
    }
    clue("Each SV is submitting status reports") {
      for (backend <- svs) {
        clue(s"The SV status reports are visible to ${backend.config.svPartyHint}") {
          eventually() {
            val svReports = backend.participantClient.ledger_api_extensions.acs
              .filterJava(SvStatusReport.COMPANION)(dsoParty, c => c.data.status.isPresent)
            inside(svReports)(reports => {
              reports.map(r => r.data.sv).toSet should equal(svPartiesSet)
            })
          }
        }
      }
    }
  }
}
