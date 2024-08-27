package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.wallet.externalparty as externalPartyCodegen
import com.daml.network.integration.tests.SpliceTests.IntegrationTest
import com.daml.network.util.{Codec, WalletTestUtil}
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

  "createExternalPartySetupProposal is returned in listExternalPartySetupProposal" in {
    implicit env =>
      val (_, proposal) = createExternalPartySetupProposal()
      val cids = aliceValidatorBackend
        .listExternalPartySetupProposal()
        .map(c => c.contract.contractId.contractId)

      cids contains proposal.contractId
  }

  "createExternalPartySetupProposal fails if a proposal for the same user already exists" in {
    implicit env =>
      loggerFactory.suppressErrors {
        createExternalPartySetupProposal()
        intercept[Throwable](createExternalPartySetupProposal()) shouldBe a[CommandFailure]
      }
  }

  "listExternalPartySetupProposal contains an empty array is no contracts exist" in {
    implicit env =>
      aliceValidatorBackend
        .listExternalPartySetupProposal() shouldBe empty
  }

  "acceptExternalPartySetupProposal can be prepared by user party" in { implicit env =>
    val (aliceUserParty, proposal) = createExternalPartySetupProposal()
    val cid = Codec
      .decodeJavaContractId(externalPartyCodegen.ExternalPartySetupProposal.COMPANION)(
        proposal.contractId
      ) match {
      case Right(cid) => cid
      case _ => throw new RuntimeException("Unable to decode contractId")
    }
    val accept = aliceValidatorBackend.prepareAcceptExternalPartySetupProposal(cid, aliceUserParty)
    accept.txHash should not be empty
    accept.transaction should not be empty
  }

  "submit acceptExternalPartySetupProposal creates TransferPreapproval" in { implicit env =>
    val OnboardingResult(party, publicKey, privateKey) = onboardExternalParty(aliceValidatorBackend)
    eventually() {
      aliceValidatorBackend.participantClient.parties
        .hosted(filterParty = party.filterString) should not be empty
    }
    val proposal = aliceValidatorBackend.createExternalPartySetupProposal(party)
    val prepare = aliceValidatorBackend.prepareAcceptExternalPartySetupProposal(proposal, party)

    val cid = aliceValidatorBackend.submitAcceptExternalPartySetupProposal(
      party,
      prepare.transaction,
      HexString.toHexString(
        crypto
          .sign(
            Hash.fromByteString(HexString.parseToByteString(prepare.txHash).value).value,
            privateKey.asInstanceOf[SigningPrivateKey],
          )
          .value
          .signature
      ),
      HexString.toHexString(publicKey.key),
    )

    aliceValidatorBackend.listTransferPreapprovals().loneElement.contractId shouldBe cid

    aliceValidatorWalletClient.tap(50.0)
    aliceValidatorBackend.getExternalPartyBalance(party).totalUnlockedCoin shouldBe "0.0000000000"
    aliceValidatorWalletClient.transferPreapprovalSend(party, 40.0)
    aliceValidatorBackend.getExternalPartyBalance(party).totalUnlockedCoin shouldBe "40.0000000000"
  }

  private def createExternalPartySetupProposal()(implicit
      env: SpliceTests.SpliceTestConsoleEnvironment
  ) = clue("Create ExternalPartySetupProposal") {
    val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    (aliceUserParty, aliceValidatorBackend.createExternalPartySetupProposal(aliceUserParty))
  }

}
