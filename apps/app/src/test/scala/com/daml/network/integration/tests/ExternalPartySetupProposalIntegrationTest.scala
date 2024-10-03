package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.transferpreapproval.TransferPreapproval
import com.daml.network.http.v0.definitions
import com.daml.network.integration.tests.SpliceTests.IntegrationTest
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.logging.SuppressingLogger
import com.digitalasset.canton.util.HexString

// TODO(#14156) Merge this into ExternallySignedPartyOnboardingTest
class ExternalPartySetupProposalIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with ExternallySignedPartyTestUtil {

  override val loggerFactory: SuppressingLogger = SuppressingLogger(getClass)

  "createExternalPartySetupProposal fails if a proposal already exists" in { implicit env =>
    loggerFactory.suppressErrors {
      val OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
      aliceValidatorBackend.createExternalPartySetupProposal(party)
      intercept[Throwable](
        aliceValidatorBackend.createExternalPartySetupProposal(party)
      ) shouldBe a[CommandFailure]
    }
  }

  "createExternalPartySetupProposal fails if a TransferPreapproval already exists" in {
    implicit env =>
      loggerFactory.suppressErrors {
        val onboarding @ OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
        createAndAcceptExternalPartySetupProposal(onboarding)
        intercept[Throwable](
          aliceValidatorBackend.createExternalPartySetupProposal(party)
        ) shouldBe a[CommandFailure]
      }
  }

  "listExternalPartySetupProposal returns an empty array if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .listExternalPartySetupProposal() shouldBe empty
  }

  "listTransferPreapprovals returns an empty array if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .listTransferPreapprovals() shouldBe empty
  }

  "TransferPreapproval allows to transfer between externally signed parties" in { implicit env =>
    // Onboard and Create/Accept ExternalPartySetupProposal for Alice
    val onboardingAlice @ OnboardingResult(aliceParty, alicePublicKey, alicePrivateKey) =
      onboardExternalParty(aliceValidatorBackend)
    aliceValidatorBackend.participantClient.parties
      .hosted(filterParty = aliceParty.filterString) should not be empty
    val (cid, _) = createAndAcceptExternalPartySetupProposal(onboardingAlice)
    aliceValidatorBackend.listTransferPreapprovals().loneElement.contractId shouldBe cid

    // Transfer 40.0 to Alice
    aliceValidatorWalletClient.tap(50.0)
    aliceValidatorBackend
      .getExternalPartyBalance(aliceParty)
      .totalUnlockedCoin shouldBe "0.0000000000"
    aliceValidatorWalletClient.transferPreapprovalSend(aliceParty, 40.0)
    aliceValidatorBackend
      .getExternalPartyBalance(aliceParty)
      .totalUnlockedCoin shouldBe "40.0000000000"

    // Onboard and Create/Accept ExternalPartySetupProposal for Bob
    val onboardingBob @ OnboardingResult(bobParty, _, _) =
      onboardExternalParty(aliceValidatorBackend)
    aliceValidatorBackend.participantClient.parties
      .hosted(filterParty = bobParty.filterString) should not be empty
    val (cidBob, _) = createAndAcceptExternalPartySetupProposal(onboardingBob)
    aliceValidatorBackend
      .listTransferPreapprovals()
      .map(tp => tp.contract.contractId) contains cidBob

    // Transfer 10.0 from Alice to Bob (with OutputFees: 6.1, SenderChangeFee: 6.0)
    val prepareSend =
      aliceValidatorBackend.prepareTransferPreapprovalSend(aliceParty, bobParty, BigDecimal(10.0))
    val updateId = aliceValidatorBackend.submitTransferPreapprovalSend(
      aliceParty,
      prepareSend.transaction,
      HexString.toHexString(
        crypto
          .sign(
            Hash.fromByteString(HexString.parseToByteString(prepareSend.txHash).value).value,
            alicePrivateKey.asInstanceOf[SigningPrivateKey],
          )
          .value
          .signature
      ),
      HexString.toHexString(alicePublicKey.key),
    )
    aliceValidatorBackend
      .getExternalPartyBalance(aliceParty)
      .totalUnlockedCoin shouldBe "17.9000000000"
    aliceValidatorBackend
      .getExternalPartyBalance(bobParty)
      .totalUnlockedCoin shouldBe "10.0000000000"

    val update = eventuallySucceeds() {
      sv1ScanBackend.getUpdate(updateId)
    }
    inside(update) {
      case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(transaction) =>
        forExactly(1, transaction.eventsById) {
          case (_, definitions.TreeEvent.members.ExercisedEvent(ev)) =>
            ev.choice shouldBe "AmuletRules_Transfer"
          case _ => fail()
        }
    }
  }

  private def createAndAcceptExternalPartySetupProposal(
      onboarding: OnboardingResult
  )(implicit
      env: SpliceTests.SpliceTestConsoleEnvironment
  ): (TransferPreapproval.ContractId, String) = {
    val proposal = aliceValidatorBackend.createExternalPartySetupProposal(onboarding.party)
    aliceValidatorBackend
      .listExternalPartySetupProposal()
      .map(c => c.contract.contractId.contractId) contains proposal.contractId

    val prepare =
      aliceValidatorBackend.prepareAcceptExternalPartySetupProposal(proposal, onboarding.party)
    prepare.txHash should not be empty
    prepare.transaction should not be empty

    aliceValidatorBackend.submitAcceptExternalPartySetupProposal(
      onboarding.party,
      prepare.transaction,
      HexString.toHexString(
        crypto
          .sign(
            Hash.fromByteString(HexString.parseToByteString(prepare.txHash).value).value,
            onboarding.privateKey.asInstanceOf[SigningPrivateKey],
          )
          .value
          .signature
      ),
      HexString.toHexString(onboarding.publicKey.key),
    )
  }

}
