package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.admin.api.client.commands.TopologyAdminCommands.Write.GenerateTransactions
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import com.digitalasset.canton.topology.transaction.*
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.version.ProtocolVersion

import java.nio.file.Files
import java.time.Duration
import scala.concurrent.duration.*

class RecoverExternalPartyIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with ExternallySignedPartyTestUtil
    with TokenStandardTest {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  "External parties can recover from key" in { implicit env =>
    val onboarding @ OnboardingResult(aliceParty, alicePublicKey, alicePrivateKey) =
      onboardExternalParty(aliceValidatorBackend, Some("aliceExternal"))

    aliceValidatorWalletClient.tap(5000.0)

    createAndAcceptExternalPartySetupProposal(
      aliceValidatorBackend,
      onboarding,
      verboseHashing = true,
    )

    actAndCheck(
      "Transfer 2000.0 to Alice via Token Standard", {
        executeTransferViaTokenStandard(
          aliceValidatorBackend.participantClientWithAdminToken,
          RichPartyId.local(aliceValidatorBackend.getValidatorPartyId()),
          aliceParty,
          BigDecimal(2000.0),
          transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct,
        )
      },
    )(
      "Alice (external party) has received the 2000.0 Amulet",
      _ => {
        aliceValidatorBackend
          .getExternalPartyBalance(aliceParty)
          .totalUnlockedCoin shouldBe "2000.0000000000"
      },
    )

    clue("Submit PartyToParticipant to migrate to bob's validator") {
      val synchronizerId =
        bobValidatorBackend.participantClient.synchronizers.list_connected().head.synchronizerId

      val partyToParticipant = PartyToParticipant
        .create(
          partyId = aliceParty,
          threshold = PositiveInt.one,
          participants = Seq(
            HostingParticipant(
              bobValidatorBackend.participantClient.id,
              ParticipantPermission.Confirmation,
            )
          ),
        )
        .value

      val txs = bobValidatorBackend.participantClient.topology.transactions.generate(
        Seq(
          GenerateTransactions.Proposal(
            partyToParticipant,
            TopologyStoreId.Synchronizer(synchronizerId),
          )
        )
      )

      val signedTxs = txs.map(sign(_, alicePrivateKey))

      val signedTxsParticipant = bobValidatorBackend.participantClient.topology.transactions.sign(
        signedTxs,
        TopologyStoreId.Synchronizer(synchronizerId),
        signedBy = Seq(bobValidatorBackend.participantClient.id.fingerprint),
      )

      bobValidatorBackend.participantClient.topology.transactions
        .load(signedTxsParticipant, TopologyStoreId.Synchronizer(synchronizerId))
    }

    // Note: This has a hard dependency on their not being any transaction for the party between
    // the validity time of the topology transaction that migrates the party and
    // the ACS import. Otherwise the participant can blow up trying to process
    // a transaction with contracts it does not yet consider active.
    clue("Import the ACS to bob's validator") {
      val acsSnapshot = sv1ScanBackend.getAcsSnapshot(aliceParty)
      val acsSnapshotFile = Files.createTempFile("acs", ".snapshot")
      Files.write(acsSnapshotFile, acsSnapshot.toByteArray())
      bobValidatorBackend.participantClient.synchronizers.disconnect_all()
      bobValidatorBackend.participantClient.repair.import_acs_old(acsSnapshotFile.toString)
      bobValidatorBackend.participantClient.synchronizers.reconnect_all()
    }

    // Tap so we have money for creating the preapproval
    bobValidatorWalletClient.tap(5000.0)
    bobValidatorWalletClient.createTransferPreapproval()

    // Grant rights to bob's validator backend the rights to prepare transactions
    // and submit signed on behalf of the party.
    bobValidatorBackend.participantClientWithAdminToken.ledger_api.users.rights.grant(
      bobValidatorBackend.getValidatorUserInfo().userName,
      actAs = Set(aliceParty),
    )

    // Create new ValidatorRight and preapprovals.
    // This is required so:
    // 1. Bob can collect validator rewards for that party.
    // 2. Bob can renew preapprovals, the old one will eventually just expire.
    // 3. Spin up our stores and ingestion for the external party.
    createAndAcceptExternalPartySetupProposal(
      bobValidatorBackend,
      onboarding,
      verboseHashing = true,
    )

    eventually() {
      bobValidatorBackend
        .getExternalPartyBalance(aliceParty)
        .totalUnlockedCoin shouldBe "2000.0000000000"
    }

    // Check that alice can transfer through bob's node
    val prepareSend =
      bobValidatorBackend.prepareTransferPreapprovalSend(
        aliceParty,
        bobValidatorBackend.getValidatorUserInfo().primaryParty,
        BigDecimal(1000.0),
        CantonTimestamp.now().plus(Duration.ofHours(24)),
        0L,
        verboseHashing = true,
      )
    val (updateId, _) = actAndCheck(timeUntilSuccess = 60.seconds)(
      "Submit signed TransferCommand creation",
      bobValidatorBackend.submitTransferPreapprovalSend(
        aliceParty,
        prepareSend.transaction,
        HexString.toHexString(
          crypto
            .signBytes(
              HexString.parseToByteString(prepareSend.txHash).value,
              alicePrivateKey.asInstanceOf[SigningPrivateKey],
              usage = SigningKeyUsage.ProtocolOnly,
            )
            .value
            .toProtoV30
            .signature
        ),
        publicKeyAsHexString(alicePublicKey),
      ),
    )(
      "validator automation completes transfer",
      _ => {
        BigDecimal(
          bobValidatorBackend
            .getExternalPartyBalance(aliceParty)
            .totalUnlockedCoin
        ) should beAround(
          BigDecimal(2000 - 1000 - 16.0 - 6.0 /* 16 output fees, 6.0 sender change fees */ )
        )
      },
    )
  }

  def sign(
      tx: TopologyTransaction[TopologyChangeOp, TopologyMapping],
      privateKey: PrivateKey,
  ): SignedTopologyTransaction[TopologyChangeOp, TopologyMapping] = {
    val sig = crypto
      .sign(
        hash = tx.hash.hash,
        signingKey = privateKey.asInstanceOf[SigningPrivateKey],
        usage = SigningKeyUsage.ProtocolOnly,
      )
      .value
    SignedTopologyTransaction.create(
      tx,
      NonEmpty(Set, SingleTransactionSignature(tx.hash, sig): TopologyTransactionSignature),
      isProposal = false,
      ProtocolVersion.dev,
    )
  }
}
