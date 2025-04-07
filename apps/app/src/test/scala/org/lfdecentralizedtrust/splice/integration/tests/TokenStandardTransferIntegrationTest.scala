package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{TriggerTestUtil, WalletTestUtil}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.crypto.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger

import java.util.UUID

class TokenStandardTransferIntegrationTest
    extends TokenStandardTest
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
  }

  "Token Standard transfer between externally signed parties" in { implicit env =>
    // Setup Alice's validator operator with featured app rights
    onboardWalletUser(aliceValidatorWalletClient, aliceValidatorBackend)
    aliceValidatorWalletClient.selfGrantFeaturedAppRight()

    // Onboard and Create/Accept ExternalPartySetupProposal for Alice
    val onboardingAlice @ OnboardingResult(aliceParty, _, alicePrivateKey) =
      onboardExternalParty(aliceValidatorBackend, Some("aliceExternal"))
    aliceValidatorBackend.participantClient.parties
      .hosted(filterParty = aliceParty.filterString) should not be empty
    aliceValidatorWalletClient.tap(50.0)
    createAndAcceptExternalPartySetupProposal(
      aliceValidatorBackend,
      onboardingAlice,
      verboseHashing = true,
    )
    eventually() {
      aliceValidatorBackend.lookupTransferPreapprovalByParty(aliceParty) should not be empty
      aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(
        aliceParty
      ) should not be empty
    }

    // Transfer 2000.0 to Alice

    aliceValidatorBackend
      .getExternalPartyBalance(aliceParty)
      .totalUnlockedCoin shouldBe "0.0000000000"
    actAndCheck(
      "Transfer 2000.0 to Alice via Token Standard", {
        executeTransferViaTokenStandard(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceValidatorBackend.getValidatorPartyId(),
          aliceParty,
          BigDecimal(2000.0),
        )
      },
    )(
      "Alice (external party) has received the 2000.0 Amulet and featured app activity was recorded for alice's validator",
      _ => {
        aliceValidatorBackend
          .getExternalPartyBalance(aliceParty)
          .totalUnlockedCoin shouldBe "2000.0000000000"
        val coupons = aliceValidatorWalletClient.listAppRewardCoupons()
        inside(coupons) { _ =>
          // It is OK to assert on the size of the list, as this is the one and only
          // test-case in this suite; and validator parties are not shared across test suites.
          coupons.size shouldBe 1
          coupons.head.payload.featured shouldBe true
        }
      },
    )

    // Onboard and Create/Accept ExternalPartySetupProposal for Bob
    val onboardingBob @ OnboardingResult(bobParty, _, _) =
      onboardExternalParty(aliceValidatorBackend, Some("bobExternal"))
    aliceValidatorBackend.participantClient.parties
      .hosted(filterParty = bobParty.filterString) should not be empty
    val (cidBob, _) =
      createAndAcceptExternalPartySetupProposal(
        aliceValidatorBackend,
        onboardingBob,
        verboseHashing = true,
      )
    eventually() {
      aliceValidatorBackend.lookupTransferPreapprovalByParty(bobParty) should not be empty
      aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(bobParty) should not be empty
    }
    aliceValidatorBackend
      .listTransferPreapprovals()
      .map(tp => tp.contract.contractId) should contain(cidBob)

    val aliceToBobFactoryChoice = transferViaTokenStandardCommands(
      aliceValidatorBackend.participantClientWithAdminToken,
      aliceParty,
      bobParty,
      BigDecimal(1000.0),
    )
    val prepareSend =
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api.interactive_submission
        .prepare(
          actAs = Seq(aliceParty),
          readAs = Seq(aliceParty),
          commands = aliceToBobFactoryChoice.commands.map(javaCommand =>
            com.daml.ledger.api.v2.commands.Command.fromJavaProto(javaCommand.toProtoCommand)
          ),
          disclosedContracts = aliceToBobFactoryChoice.disclosedContracts.map(x =>
            com.daml.ledger.api.v2.commands.DisclosedContract.fromJavaProto(x)
          ),
          synchronizerId = Some(decentralizedSynchronizerId),
          verboseHashing = true,
        )
    prepareSend.hashingDetails should not be empty
    val submissionId = UUID.randomUUID().toString
    val (_, _) = actAndCheck(
      "Transfer 1000.0 from Alice to Bob",
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api.interactive_submission
        .execute(
          prepareSend.getPreparedTransaction,
          Map(
            aliceParty -> Seq(
              crypto
                .signBytes(
                  prepareSend.preparedTransactionHash,
                  alicePrivateKey.asInstanceOf[SigningPrivateKey],
                  usage = SigningKeyUsage.ProtocolOnly,
                )
                .valueOrFail("Couldn't sign with alice's private key")
            )
          ),
          submissionId = submissionId,
          hashingSchemeVersion = prepareSend.hashingSchemeVersion,
        ),
    )(
      "validator automation completes transfer",
      _ => {
        aliceValidatorBackend
          .getExternalPartyBalance(bobParty)
          .totalUnlockedCoin shouldBe "1000.0000000000"
        BigDecimal(
          aliceValidatorBackend
            .getExternalPartyBalance(aliceParty)
            .totalUnlockedCoin
        ) should beAround(
          BigDecimal(2000 - 1000 - 16.0 - 6.0 /* 16 output fees, 6.0 sender change fees */ )
        )
      },
    )
  }

}
