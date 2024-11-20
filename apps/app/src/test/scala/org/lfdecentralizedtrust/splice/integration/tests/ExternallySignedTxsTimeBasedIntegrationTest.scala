package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.interactive.InteractiveSubmissionServiceOuterClass.PreparedTransaction
import com.digitalasset.canton.crypto.SigningPrivateKey
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.util.HexString
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, WalletTestUtil}

import java.time.Duration
import java.util.Base64

class ExternallySignedTxsTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with ExternallySignedPartyTestUtil
    with TimeTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)

  "Externally signed transactions can tolerate a preparation/submission skew upto the submissionTImeRecordTimeTolerance window" in {
    implicit env =>
      aliceValidatorWalletClient.tap(100.0)

      // Onboard external party 1
      val onboarding1 = onboardExternalParty(aliceValidatorBackend)
      val proposal1 = createExternalPartySetupProposal(aliceValidatorBackend, onboarding1)
      val preparePartySetup1 =
        prepareAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding1, proposal1)
      val preparedTxPartySetup =
        PreparedTransaction.parseFrom(Base64.getDecoder.decode(preparePartySetup1.transaction))

      // Advance time by a little less than the submissionTimeRecordTimeTolerance of 24h
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
            CantonTimestamp.ofEpochMicro(preparedTxPartySetup.getMetadata.getSubmissionTime)
          val actualRecordTime =
            CantonTimestamp.assertFromInstant(java.time.Instant.parse(transaction.recordTime))
          val expectedRecordTime = submissionTime.plus(Duration.ofHours(23))
          // We allow for a 1s buffer since time is not completely still in simtime, the microseconds still advance.
          actualRecordTime should be >= expectedRecordTime
          actualRecordTime should be < expectedRecordTime.plus(Duration.ofSeconds(1))
      }

      // Transfer some funds to external party 1
      actAndCheck(
        "Transfer some amulets to external party 1",
        aliceValidatorWalletClient.transferPreapprovalSend(onboarding1.party, 50.0, ""),
      )(
        "External party 1 sees the transfer",
        _ =>
          aliceValidatorBackend
            .getExternalPartyBalance(onboarding1.party)
            .totalUnlockedCoin shouldBe "50.0000000000",
      )

      // Onboard external party 2
      val onboarding2 = onboardExternalParty(aliceValidatorBackend)
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
        )
      val preparedTxSend =
        PreparedTransaction.parseFrom(Base64.getDecoder.decode(prepareSend.transaction))

      // Advance time by a little less than the submissionTimeRecordTimeTolerance of 24h
      advanceTime(Duration.ofHours(23))
      actAndCheck(
        "Submit signed TransferCommand creation",
        aliceValidatorBackend.submitTransferPreapprovalSend(
          onboarding1.party,
          prepareSend.transaction,
          HexString.toHexString(
            crypto
              .signBytes(
                HexString.parseToByteString(prepareSend.txHash).value,
                onboarding1.privateKey.asInstanceOf[SigningPrivateKey],
              )
              .value
              .signature
          ),
          HexString.toHexString(onboarding1.publicKey.key),
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
                CantonTimestamp.ofEpochMicro(preparedTxSend.getMetadata.getSubmissionTime)
              val actualRecordTime =
                CantonTimestamp.assertFromInstant(java.time.Instant.parse(transaction.recordTime))
              val expectedRecordTime = submissionTime.plus(Duration.ofHours(23))
              // We allow for a 1s buffer since time is not completely still in simtime, the microseconds still advance.
              actualRecordTime should be >= expectedRecordTime
              actualRecordTime should be < expectedRecordTime.plus(Duration.ofSeconds(1))
          }
        },
      )

  }
}
