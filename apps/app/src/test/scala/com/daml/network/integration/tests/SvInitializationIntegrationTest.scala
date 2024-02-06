package com.daml.network.integration.tests

import cats.implicits.catsSyntaxParallelTraverse1
import com.daml.network.console.{
  ScanAppBackendReference,
  SvAppBackendReference,
  ValidatorAppBackendReference,
}
import com.daml.network.environment.RetryFor
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.transaction.ParticipantPermissionX
import com.digitalasset.canton.util.FutureInstances.parallelFuture

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

class SvInitializationIntegrationTest extends SvIntegrationTestBase {

  "start and restart cleanly" in { implicit env =>
    initSvc()
    sv1Backend.stop()
    sv1Backend.startSync()
  }

  "can start before its validator app" in { implicit env =>
    // we want this so we can have a clear init dependency validator app -> sv app
    clue("Starting founding SV's SV app") {
      sv1Backend.startSync()
    }
    clue("Starting founding SV's scan app") {
      // validators need this
      sv1ScanBackend.startSync()
    }
    clue("Starting founding SV's validator app") {
      sv1ValidatorBackend.startSync()
    }
    clue("Starting joining SV's SV app") {
      sv2Backend.startSync()
    }
    clue("Starting joining SV's validator app") {
      sv2ValidatorBackend.startSync()
    }
  }

  "connect to all domains during initialization" in { implicit env =>
    initSvc()
    sv4Backend.stop()

    val globalDomainId = inside(sv4Backend.participantClient.domains.list_connected()) {
      case Seq(domain) =>
        domain.domainId
    }

    clue("simulate the domain was left disconnected when error occur during party migration.") {
      sv4Backend.participantClient.domains.disconnect_all()
      sv4Backend.participantClient.domains.list_connected() shouldBe empty
    }

    clue("sv will connect to all domains during initialization.") {
      sv4Backend.startSync()
      inside(sv4Backend.participantClient.domains.list_connected()) {
        case Seq(listConnectedDomainsResult) =>
          listConnectedDomainsResult.domainId shouldBe globalDomainId
      }
    }
  }

  // A test to make debugging bootstrap problems easier
  "SV apps can start one by one" in { implicit env =>
    import env.executionContext
    clue("Starting SVC app and SV1 app") {
      startAllSync(sv1ScanBackend, sv1ValidatorBackend, sv1Backend)
    }

    def startSv(
        number: Int,
        sv: SvAppBackendReference,
        validator: ValidatorAppBackendReference,
        scanApp: Option[ScanAppBackendReference] = None,
    ) =
      clue(s"Starting SV$number app") {
        validator.start()
        sv.start()
        scanApp.foreach(_.start())
        validator.waitForInitialization()
        sv.waitForInitialization()
        scanApp.foreach(_.waitForInitialization())
      }

    startSv(2, sv2Backend, sv2ValidatorBackend, Some(sv2ScanBackend))
    startSv(3, sv3Backend, sv3ValidatorBackend)
    // Increase the decentralized namespace threshold to 3 to require more than the candidate and sponsor to authorize the party to participant mapping. This ensures that the party to participant reconciliation loops work as expected.
    // do this by falsely adding the sequencer namespace to the decentralized namespace
    val sv1SequencerAdminConnection =
      sv1Backend.appState.localDomainNode.value.sequencerAdminConnection
    val sv1SequencerId = sv1SequencerAdminConnection.getSequencerId.futureValue
    val newDecentralizedNamespace = Seq(
      sv1SequencerAdminConnection,
      sv1Backend.appState.participantAdminConnection,
      sv2Backend.appState.participantAdminConnection,
    ).parTraverse { connection =>
      val id = connection.getId().futureValue
      connection
        .ensureDecentralizedNamespaceDefinitionProposalAccepted(
          globalDomainId,
          svcParty.uid.namespace,
          sv1SequencerId.uid.namespace,
          id.namespace.fingerprint,
          RetryFor.WaitingOnInitDependency,
        )
    }.futureValue
      .headOption
      .value
    newDecentralizedNamespace.mapping.threshold shouldBe PositiveInt.tryCreate(3)

    try {
      startSv(4, sv4Backend, sv4ValidatorBackend)

      clue("All SVs have reported their Scan URLs in SVC rules") {
        eventually() {
          val svcRules = sv1Backend.appState.svcStore.getSvcRules().futureValue.contract.payload
          svcRules.members.asScala
            .flatMap(_._2.domainNodes.get(globalDomainId.toProtoPrimitive).scan.toScala)
            // onle sv1 and sv2 have scan apps
            .map(_.publicUrl) should contain theSameElementsAs Seq(
            "http://localhost:5012",
            "http://localhost:5112",
          )
        }
      }
    } finally {
      // Remove the sequencer again, otherwise the logic for resetting the namespace to only contain
      // sv1 will fail.
      Seq(
        sv1Backend.appState.participantAdminConnection,
        sv2Backend.appState.participantAdminConnection,
        sv3Backend.appState.participantAdminConnection,
        sv4Backend.appState.participantAdminConnection,
      ).parTraverse { connection =>
        val id = connection.getId().futureValue
        connection
          .ensureDecentralizedNamespaceDefinitionRemovalProposal(
            globalDomainId,
            svcParty.uid.namespace,
            sv1SequencerId.uid.namespace,
            id.namespace.fingerprint,
            RetryFor.WaitingOnInitDependency,
          )
      }.futureValue
        .headOption
        .value
    }
  }

  "The SVC is bootstrapped correctly" in { implicit env =>
    initSvc()
    val svcRules = clue("An SvcRules contract exists") {
      sv1Backend.getSvcInfo()
    }
    val svParties = clue("We have four sv parties and their apps are online") {
      svs.map(_.getSvcInfo().svParty.toProtoPrimitive)
    }
    clue("The four SV apps are all SVC members and there are no other SVC members") {
      sv1Backend.getSvcInfo().svcRules.payload.members.keySet() should equal(svParties.toSet.asJava)
    }
    clue("The founding SV app (sv1) is the first leader") {
      sv1Backend.getSvcInfo().svcRules.payload.leader should equal(
        svcRules.svParty.toProtoPrimitive
      )
    }
    clue("initial open mining rounds are created") {
      eventually() {
        sv1Backend.listOpenMiningRounds() should have size 3
        sv1ScanBackend.getOpenAndIssuingMiningRounds()._1 should have size 3
        sv2ScanBackend.getOpenAndIssuingMiningRounds()._1 should have size 3
      }
    }
    clue("thresholds are set as expected") {
      eventually() {
        val participantAdminConnection = sv1Backend.appState.participantAdminConnection
        participantAdminConnection
          .getDecentralizedNamespaceDefinition(
            globalDomainId,
            svcParty.uid.namespace,
          )
          .futureValue
          .mapping
          .threshold shouldBe PositiveInt.tryCreate(3)
        participantAdminConnection
          .getSequencerDomainState(globalDomainId)
          .futureValue
          .mapping
          .threshold shouldBe PositiveInt.tryCreate(2)
        participantAdminConnection
          .getMediatorDomainState(globalDomainId)
          .futureValue
          .mapping
          .threshold shouldBe PositiveInt.tryCreate(2)
        participantAdminConnection
          .getPartyToParticipant(globalDomainId, svcParty)
          .futureValue
          .mapping
          .threshold
          .value shouldBe 3
      }
    }
    clue("SV participants have submission rights on behalf of the SVC party") {
      eventually() {
        val participantAdminConnection = sv1Backend.appState.participantAdminConnection
        val svcHostingParticipants = participantAdminConnection
          .getPartyToParticipant(globalDomainId, svcParty)
          .futureValue
          .mapping
          .participants
        svcHostingParticipants should have length 4
        svcHostingParticipants.foreach(_.permission shouldBe ParticipantPermissionX.Submission)
      }
    }
  }

}
