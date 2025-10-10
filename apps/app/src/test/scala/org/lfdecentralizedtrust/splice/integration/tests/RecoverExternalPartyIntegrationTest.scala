package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.ValidatorRewardCoupon
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
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
import org.lfdecentralizedtrust.splice.util.WalletTestUtil

import java.nio.file.Files
import java.time.Duration
import scala.concurrent.duration.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class RecoverExternalPartyIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with ExternallySignedPartyTestUtil
    with TokenStandardTest
    with WalletTestUtil {

  override def environmentDefinition: EnvironmentDefinition =
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID
  )

  override lazy val sanityChecksIgnoredRootExercises = Seq(
    (ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID, "Archive")
  )

  "External parties can recover from key" in { implicit env =>
    // Canton endpoint is kinda slow for this so we only resolve it once.
    val synchronizerId = decentralizedSynchronizerId

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

    val signedTx = clue("Sign PartyToParticipant to migrate to bob's validator") {

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

      // Note: This only signs it does not upload.
      bobValidatorBackend.participantClient.topology.transactions.sign(
        signedTxs,
        TopologyStoreId.Synchronizer(synchronizerId),
        signedBy = Seq(bobValidatorBackend.participantClient.id.fingerprint),
      )

    }

    val rewardCid =
      clue("Create a contract that will be included in the ACS import and can easily be archived") {
        // ValidatorRewardCoupon just acts as an example that we can easily create and easily archive
        // as it only has a single signatory and an observer.
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(dsoParty),
            readAs = Seq.empty,
            update = new ValidatorRewardCoupon(
              dsoParty.toProtoPrimitive,
              aliceParty.toProtoPrimitive,
              BigDecimal(42.0).bigDecimal,
              new Round(0),
            ).create,
          )
          .contractId
      }

    bobValidatorBackend.participantClient.synchronizers.disconnect_all()

    val partyMigrationTime = clue("Submit PartyToParticipant") {
      // Bob is disconnected so we have to submit the transaction through another participant.
      // Any participant works here.
      // If users don't have access to another one, they could:
      // 1. Migrate to a fresh participant and submit the topology transaction from that. Given that it is a new participant if it blows up due to concurrent activity
      //    they can just reset it and try again.
      // 2. Spin up a temporary one just for submitting the topology transaction although that is difficult in practice on mainnet.
      // 3. Ask any of the other validators to submit it for them.
      // While in theory we could allow submitting it through SV participants via scan, rate limiting that
      // is a bit tricky so we don't invest into that for now given that with online party migration this
      // will be less of an issue.
      sv1Backend.participantClient.topology.transactions
        .load(signedTx, TopologyStoreId.Synchronizer(synchronizerId))
      clue("PartyToParticipant transaction gets sequenced") {
        eventually() {
          val topologyTx = sv1Backend.participantClient.topology.party_to_participant_mappings
            .list(synchronizerId, filterParty = aliceParty.filterString)
            .loneElement
          topologyTx.item.participants.loneElement.participantId shouldBe bobValidatorBackend.participantClient.id
          topologyTx.context.validFrom
        }
      }
    }

    clue("Use a contract in the ACS before bob imports the ACS") {
      // Note: This blows up if Bob is not disconnected while the party to participant
      // change becomes valid as it does not yet know about the contract.
      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
        userId = sv1Backend.config.ledgerApiUser,
        actAs = Seq(dsoParty),
        readAs = Seq.empty,
        update = rewardCid.exerciseArchive(),
      )
    }

    clue("Import the ACS to bob's validator") {
      // This can fail if sv1 participant is not yet ready to serve the ACS at that timestamp.
      val acsSnapshot = eventuallySucceeds() {
        sv1ScanBackend.getAcsSnapshot(aliceParty, Some(partyMigrationTime))
      }
      val acsSnapshotFile = Files.createTempFile("acs", ".snapshot")
      Files.write(acsSnapshotFile, acsSnapshot.toByteArray())
      bobValidatorBackend.participantClient.repair.import_acs_old(acsSnapshotFile.toString)
      bobValidatorBackend.participantClient.synchronizers.reconnect_all()
    }

    // Tap so we have money for creating the preapproval
    bobValidatorWalletClient.tap(5000.0)
    createTransferPreapprovalEnsuringItExists(bobValidatorWalletClient, bobValidatorBackend)

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
        Some("transfer-command-description"),
        verboseHashing = true,
      )
    val (updateId, _) = actAndCheck(timeUntilSuccess = 60.seconds)(
      "Submit signed TransferCommand creation",
      bobValidatorBackend.submitTransferPreapprovalSend(
        aliceParty,
        prepareSend.transaction,
        HexString.toHexString(
          crypto(env.executionContext)
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
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): SignedTopologyTransaction[TopologyChangeOp, TopologyMapping] = {
    val sig = crypto(env.executionContext)
      .sign(
        hash = tx.hash.hash,
        signingKey = privateKey.asInstanceOf[SigningPrivateKey],
        usage = SigningKeyUsage.ProtocolOnly,
      )
      .value
    SignedTopologyTransaction.withTopologySignatures(
      tx,
      NonEmpty(Seq, SingleTransactionSignature(tx.hash, sig): TopologyTransactionSignature),
      isProposal = false,
      ProtocolVersion.v34,
    )
  }
}
