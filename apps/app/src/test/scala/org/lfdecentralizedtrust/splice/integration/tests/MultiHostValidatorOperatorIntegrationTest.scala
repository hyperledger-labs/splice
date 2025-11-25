package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands.Write.GenerateTransactions
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.participant.admin.data.ContractImportMode

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

import java.nio.file.Files

class MultiHostValidatorOperatorIntegrationTest
  extends IntegrationTestWithSharedEnvironment
  with WalletTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // TODO(#979) Consider removing this once domain config updates are less disruptive to carefully-timed batching tests.
      .withSequencerConnectionsFromScanDisabled()

  "validator operator can be multi-hosted and work with transfer preapprovals" in {
      implicit env =>
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val operatorParty = aliceValidatorBackend.getValidatorPartyId()
        val synchronizerId =
          aliceValidatorBackend.participantClient.synchronizers.list_connected()(0).synchronizerId
        val bobParticipantId = bobValidatorBackend.participantClient.id

        // Multi-host operatorParty (the operator of aliceValidator)
        // also on bobValidator, per the Canton docs [here](https://docs.digitalasset.com/operate/3.4/howtos/operate/parties/party_replication.html#permission-change-replication-procedure)

        actAndCheck(
          "BobValidator proposes to host operatorParty",
          bobValidatorBackend.participantClient.topology.party_to_participant_mappings.propose_delta(
            party = operatorParty,
            adds = Seq((bobParticipantId, ParticipantPermission.Observation)),
            store = synchronizerId,
            requiresPartyToBeOnboarded = true
          )
        )(
          "AliceValidator sees the proposal",
          _ =>
            aliceValidatorBackend.participantClient.topology.party_to_participant_mappings.list_hosting_proposals(synchronizerId, bobParticipantId) should not be empty
        )

        actAndCheck(
          "Disconnect bobValidator",
          {
            // We first stop the validator app so it doesn't fail due to the synchronizer being disconnected
            bobValidatorBackend.stop()
            bobValidatorBackend.participantClient.synchronizers.disconnect_all()
          }
        )(
          "bobValidator is disconnected",
          _ => bobValidatorBackend.participantClient.synchronizers.list_connected() shouldBe empty
        )

        val beforeActivationOffset = aliceValidatorBackend.participantClient.ledger_api.state.end()


        clue("AliceValidator agrees to the topology change") {
          aliceValidatorBackend.participantClient.topology.party_to_participant_mappings
            .propose_delta(
              party = operatorParty,
              adds = Seq((bobParticipantId, ParticipantPermission.Observation)),
              store = synchronizerId,
              requiresPartyToBeOnboarded = true
            )
        }

        val acsFile = Files.createTempFile("operator", ".acs")
        clue("Export ACS") {
//          eventuallySucceeds() {
            println("Exporting ACS")
            aliceValidatorBackend.participantClient.parties
              .export_party_acs(
                party = operatorParty,
                synchronizerId = synchronizerId,
                targetParticipantId = bobParticipantId,
                beginOffsetExclusive = beforeActivationOffset,
                exportFilePath = acsFile.toString,
              )
//          }
        }
        clue("Import ACS") {
          bobValidatorBackend.participantClient.parties
            .import_party_acs(acsFile.toString, contractImportMode = ContractImportMode.Accept)
        }

      clue("Reconnect synchronizer and start the validator app again") {
        bobValidatorBackend.participantClient.synchronizers.reconnect_all()
        bobValidatorBackend.startSync()
      }

        actAndCheck(
          "Clear the onboarding flag",
        bobValidatorBackend.participantClient.topology.party_to_participant_mappings
          .propose_delta(
            party = operatorParty,
            adds = Seq((bobParticipantId, ParticipantPermission.Observation)),
            store = synchronizerId,
          )
        )(
          "Alice sees the transaction",
          // FIXME: in the docs, this is "wait for decision timeout". Can we replace by "alice sees it"?
          _ => aliceValidatorBackend.participantClient.topology.party_to_participant_mappings.list_hosting_proposals(synchronizerId, bobParticipantId) should not be empty
        )

  }

}
