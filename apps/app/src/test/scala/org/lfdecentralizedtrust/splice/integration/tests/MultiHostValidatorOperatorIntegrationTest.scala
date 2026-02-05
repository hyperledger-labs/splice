package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.transaction.*
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryRequest
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import org.lfdecentralizedtrust.splice.store.Limit

import java.nio.file.Files
import java.util.UUID
import scala.concurrent.duration.*

class MultiHostValidatorOperatorIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()
      // Disable traffic topups as they can end up failing if we disconnect the node at the same time
      // which then results in record order publishing issues on SV1's participant.
      .withTrafficTopupsDisabled

  "validator operator can be multi-hosted and work with transfer preapprovals" in { implicit env =>
    val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val operatorParty = aliceValidatorBackend.getValidatorPartyId()
    val globalDomainAlias = sv1Backend.config.domains.global.alias
    val aliceParticipant = aliceValidatorBackend.participantClient
    val synchronizerId = aliceParticipant.synchronizers.id_of(globalDomainAlias)
    val bobParticipant = bobValidatorBackend.participantClient
    val bobParticipantId = bobParticipant.id

    // Multi-host operatorParty (the operator of aliceValidator), and aliceUser also on bobValidator,
    // per the Canton docs [here](https://docs.digitalasset.com/operate/3.4/howtos/operate/parties/party_replication.html#permission-change-replication-procedure)

    aliceParticipant.topology.party_to_participant_mappings
      .list_hosting_proposals(synchronizerId, bobParticipantId) shouldBe empty

    val multiHostedParties = Seq(operatorParty, aliceUserParty)
    actAndCheck(
      "BobValidator proposes to host the parties",
      multiHostedParties.foreach(party =>
        bobParticipant.topology.party_to_participant_mappings.propose_delta(
          party,
          adds = Seq((bobParticipantId, ParticipantPermission.Observation)),
          store = synchronizerId,
          requiresPartyToBeOnboarded = true,
        )
      ),
    )(
      "AliceValidator sees the proposals",
      _ =>
        aliceParticipant.topology.party_to_participant_mappings
          .list_hosting_proposals(synchronizerId, bobParticipantId)
          .length shouldBe 2,
    )

    clue("Disconnect bobValidator") {
      // We first stop the validator app so it doesn't fail due to the synchronizer being disconnected
      bobValidatorBackend.stop()
      bobParticipant.synchronizers.disconnect_all()
    }

    val beforeActivationOffset = aliceParticipant.ledger_api.state.end()
    actAndCheck(timeUntilSuccess = 5.minutes)(
      "AliceValidator agrees to the topology changes", {
        multiHostedParties.foreach(party =>
          aliceParticipant.topology.party_to_participant_mappings
            .propose_delta(
              party,
              adds = Seq((bobParticipantId, ParticipantPermission.Observation)),
              store = synchronizerId,
              requiresPartyToBeOnboarded = true,
            )
        )
      },
    )(
      "Topology has changed",
      _ => {
        forEvery(multiHostedParties)(party =>
          aliceParticipant.topology.party_to_participant_mappings
            .list(
              synchronizerId,
              filterParty = party.toProtoPrimitive,
              filterParticipant = bobParticipantId.toProtoPrimitive,
            ) should not be empty
        )
      },
    )

    multiHostedParties.foreach(party => {
      val acsFile = Files.createTempFile(party.toString, ".acs")
      clue(s"Export ACS for $party") {
        aliceParticipant.parties
          .export_party_acs(
            party,
            synchronizerId = synchronizerId,
            targetParticipantId = bobParticipantId,
            beginOffsetExclusive = beforeActivationOffset,
            exportFilePath = acsFile.toString,
          )
      }
      clue(s"Import ACS for $party") {
        bobParticipant.parties.import_party_acs(
          importFilePath = acsFile.toString
        )
      }
    })

    clue("start the validator app again (which will also reconnect the participant)") {
      bobValidatorBackend.startSync()
    }

    actAndCheck(
      "Clear the onboarding flags", {
        multiHostedParties.map(party =>
          bobParticipant.topology.party_to_participant_mappings
            .propose_delta(
              party,
              adds = Seq((bobParticipantId, ParticipantPermission.Observation)),
              store = synchronizerId,
            )
        )
      },
    )(
      "Alice sees the transaction",
      _ =>
        forEvery(multiHostedParties) { party =>
          val mappings = aliceParticipant.topology.party_to_participant_mappings
            .list(
              synchronizerId,
              filterParty = party.toProtoPrimitive,
              filterParticipant = bobParticipantId.toProtoPrimitive,
            )
          mappings should not be empty
          mappings.last.item.participants.last.onboarding shouldBe false
        },
    )

    actAndCheck(
      "Grant confirmation rights",
      multiHostedParties.foreach(party =>
        Seq(aliceValidatorBackend, bobValidatorBackend).foreach(
          _.participantClient.topology.party_to_participant_mappings
            .propose_delta(
              party,
              adds = Seq((bobParticipantId, ParticipantPermission.Confirmation)),
              store = synchronizerId,
            )
        )
      ),
    )(
      "Wait for confirmation rights",
      _ =>
        forEvery(multiHostedParties) { party =>
          val mapping =
            aliceValidatorBackend.participantClient.topology.party_to_participant_mappings
              .list(
                synchronizerId,
                filterParty = party.toProtoPrimitive,
                filterParticipant = bobParticipantId.toProtoPrimitive,
              )
          mapping should not be empty
          mapping.last.item.participants.last.permission shouldBe ParticipantPermission.Confirmation
        },
    )

    clue("Setup transfer preapproval") {
      // Tap some amulet, so the validator has funds to cover the transfer preapproval creation fee
      aliceValidatorWalletClient.tap(10)
      createTransferPreapprovalEnsuringItExists(aliceWalletClient, aliceValidatorBackend)
    }

    aliceWalletClient.balance().unlockedQty should beAround(0.0)
    clue("Stop alice validator app, and disconnect its participant") {
      aliceValidatorBackend.stop()
      aliceParticipant.synchronizers.disconnect_all()
    }

    splitwellWalletClient.tap(walletAmuletToUsd(100))
    splitwellWalletClient.balance().unlockedQty should beAround(100.0)
    val deduplicationId = UUID.randomUUID.toString
    val transferDescription = Some("test-description")
    actAndCheck(
      "Splitwell sends Alice 40.0 amulet",
      splitwellWalletClient.transferPreapprovalSend(
        aliceUserParty,
        40.0,
        deduplicationId,
        transferDescription,
      ),
    )(
      "The send succeeds despite alice's validator being disconnected and stopped",
      _ => {
        // Fees eat up quite a bit
        splitwellWalletClient.balance().unlockedQty should beWithin(47, 48)
        // Alice's wallet is stopped, so we confirm the transaction via scan
        sv1ScanBackend
          .listTransactions(
            None,
            TransactionHistoryRequest.SortOrder.Desc,
            Limit.DefaultMaxPageSize,
          )
          .flatMap(_.transfer)
          .filter(tf => tf.description == transferDescription) should not be empty
      },
    )
  }
}
