package com.daml.network.integration.tests

import com.daml.network.integration.tests.SpliceTests.IntegrationTest
import com.daml.network.util.WalletTestUtil
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
      val proposal = createExternalPartySetupProposal()
      val cids = aliceValidatorBackend
        .listExternalPartySetupProposal()
        .map(c => c.contract.contractId.contractId)

      cids contains proposal.contractId
  }

  "createExternalPartySetupProposal fails a proposal for the same user already exists" in {
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

  private def createExternalPartySetupProposal()(implicit
      env: SpliceTests.SpliceTestConsoleEnvironment
  ) = clue("Create ExternalPartySetupProposal") {
    val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    aliceValidatorBackend.createExternalPartySetupProposal(aliceUserParty)
  }

}
