package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.wallet.externalparty as externalPartyCodegen
import com.daml.network.integration.tests.SpliceTests.IntegrationTest
import com.daml.network.util.{Codec, WalletTestUtil}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.logging.SuppressingLogger

class ExternalPartySetupProposalIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil {

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

  private def createExternalPartySetupProposal()(implicit
      env: SpliceTests.SpliceTestConsoleEnvironment
  ) = clue("Create ExternalPartySetupProposal") {
    val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    (aliceUserParty, aliceValidatorBackend.createExternalPartySetupProposal(aliceUserParty))
  }

}
