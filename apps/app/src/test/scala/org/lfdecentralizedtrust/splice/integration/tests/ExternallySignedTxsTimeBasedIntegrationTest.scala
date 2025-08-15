package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PreparedTransaction
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.util.HexString
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import java.time.Duration
import java.util.Base64

// does token standard stuff
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class ExternallySignedTxsTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with ExternallySignedPartyTestUtil
    with TimeTestUtil
    with TokenStandardTest {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)

  "Externally signed transactions can tolerate a preparation/submission skew larger than ledgerTimeRecordTimeTolerance" in {
    implicit env =>
      aliceValidatorWalletClient.tap(200.0)

      // Onboard external party 1
      val onboarding1 = onboardExternalParty(aliceValidatorBackend, Some("externalParty1"))
      val proposal1 = createExternalPartySetupProposal(aliceValidatorBackend, onboarding1)
      val preparePartySetup1 =
        prepareAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding1, proposal1)
      val preparedTxPartySetup =
        PreparedTransaction.parseFrom(Base64.getDecoder.decode(preparePartySetup1.transaction))

      // Advance time by a little less than the preparationTimeRecordTimeTolerance of 24h
      advanceTime(Duration.ofHours(23))

      // Submit externally signed transaction
      val (_, updateId) =
        submitExternalPartySetupProposal(aliceValidatorBackend, onboarding1, preparePartySetup1)
      val update = eventuallySucceeds() {
        sv1ScanBackend.getUpdate(updateId, encoding = CompactJson)
      }
      inside(update) {
        case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(transaction) =>
          val submissionTime =
            CantonTimestamp.ofEpochMicro(preparedTxPartySetup.getMetadata.getPreparationTime)
          val actualRecordTime =
            CantonTimestamp.assertFromInstant(java.time.Instant.parse(transaction.recordTime))
          val expectedRecordTime = submissionTime.plus(Duration.ofHours(23))
          // We allow for a 1s buffer since time is not completely still in simtime, the microseconds still advance.
          actualRecordTime should be >= expectedRecordTime
          actualRecordTime should be < expectedRecordTime.plus(Duration.ofSeconds(1))
      }

      // Advance time by a few seconds more to avoid triggers not working due to them noticing
      // the large time skew and holding off acting with the log-line:
      //  INFO - o.l.s.s.DomainTimeStore:ExternallySignedTxsTimeBasedIntegrationTest/config=2ce1e8c6/validator=sv1Validator (b1b7a63c6a0034caa822210a130ad4fa-ValidatorPackageVettingTrigger--6e0e69cabf8e598b) - Domain time delay is currently 22h 59m 59.999722s (1970-01-02T23:50:56Z - 1970-01-02T00:50:56.000278Z), waiting until delay is below 2 minutes. This is expected if the node restored from backup
      advanceTime(Duration.ofSeconds(5))

      // Transfer some funds to external party 1
      actAndCheck(
        "Transfer some amulets to external party 1",
        aliceValidatorWalletClient.transferPreapprovalSend(onboarding1.party, 100.0, ""),
      )(
        "External party 1 sees the transfer",
        _ =>
          aliceValidatorBackend
            .getExternalPartyBalance(onboarding1.party)
            .totalUnlockedCoin shouldBe "100.0000000000",
      )

      // Onboard external party 2
      val onboarding2 = onboardExternalParty(aliceValidatorBackend, Some("externalParty2"))
      val proposal2 = createExternalPartySetupProposal(aliceValidatorBackend, onboarding2)
      val preparePartySetup2 =
        prepareAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding2, proposal2)
      submitExternalPartySetupProposal(aliceValidatorBackend, onboarding2, preparePartySetup2)

      // Transfer 10 amulets from ext party 1 to ext party 2
      val prepareSend =
        aliceValidatorBackend.prepareTransferPreapprovalSend(
          onboarding1.party,
          onboarding2.party,
          BigDecimal(10.0),
          CantonTimestamp.now().plus(Duration.ofHours(24)),
          0L,
          Some("transfer-command-description"),
        )
      val preparedTxSend =
        PreparedTransaction.parseFrom(Base64.getDecoder.decode(prepareSend.transaction))

      // Advance time by a little less than the preparationTimeRecordTimeTolerance of 24h
      advanceTime(Duration.ofHours(23))
      actAndCheck(
        "Submit signed TransferCommand creation",
        aliceValidatorBackend.submitTransferPreapprovalSend(
          onboarding1.party,
          prepareSend.transaction,
          HexString.toHexString(
            crypto(env.executionContext)
              .signBytes(
                HexString.parseToByteString(prepareSend.txHash).value,
                onboarding1.privateKey.asInstanceOf[SigningPrivateKey],
                usage = SigningKeyUsage.ProtocolOnly,
              )
              .value
              .toProtoV30
              .signature
          ),
          publicKeyAsHexString(onboarding1.publicKey),
        ),
      )(
        "Validator automation completes transfer",
        updateId => {
          val result = aliceValidatorBackend.scanProxy
            .lookupTransferCommandStatus(
              onboarding1.party,
              0L,
            )
            .value
          result.transferCommandsByContractId should have size 1
          val transferCommandCid = result.transferCommandsByContractId.keys
            .find(_.startsWith(prepareSend.transferCommandContractIdPrefix))
            .value
          result.transferCommandsByContractId
            .get(transferCommandCid)
            .value
            .status shouldBe definitions.TransferCommandContractStatus.members
            .TransferCommandSentResponse(
              definitions.TransferCommandSentResponse(status = "sent")
            )
          aliceValidatorBackend
            .getExternalPartyBalance(onboarding2.party)
            .totalUnlockedCoin shouldBe "10.0000000000"
          val update = eventuallySucceeds() {
            sv1ScanBackend.getUpdate(updateId, encoding = CompactJson)
          }
          inside(update) {
            case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(transaction) =>
              val submissionTime =
                CantonTimestamp.ofEpochMicro(preparedTxSend.getMetadata.getPreparationTime)
              val actualRecordTime =
                CantonTimestamp.assertFromInstant(java.time.Instant.parse(transaction.recordTime))
              val expectedRecordTime = submissionTime.plus(Duration.ofHours(23))
              // We allow for a 1s buffer since time is not completely still in simtime, the microseconds still advance.
              actualRecordTime should be >= expectedRecordTime
              actualRecordTime should be < expectedRecordTime.plus(Duration.ofSeconds(1))
          }
        },
      )

      val beforePrepare = env.environment.clock.now

      executeTransferViaTokenStandard(
        aliceValidatorBackend.participantClientWithAdminToken,
        onboarding1.richPartyId,
        onboarding2.party,
        BigDecimal("10.0"),
        transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct,
        expectedTimeBounds =
          Some((beforePrepare, beforePrepare.plusSeconds(10 * 60).addMicros(-1))),
        advanceTimeBeforeExecute = Some(Duration.ofMinutes(9)),
      )

      val afterExecute = env.environment.clock.now
      afterExecute shouldBe beforePrepare.plus(Duration.ofMinutes(9))

  }
}
