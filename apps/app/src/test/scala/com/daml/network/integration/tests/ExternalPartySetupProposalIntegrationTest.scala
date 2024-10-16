package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.amulet as amuletCodegen
import com.daml.network.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.http.v0.definitions
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.IntegrationTest
import com.daml.network.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import com.daml.network.util.{TriggerTestUtil, WalletTestUtil}
import com.daml.network.validator.automation.RenewTransferPreapprovalTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.crypto.*
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressingLogger
import com.digitalasset.canton.util.HexString
import monocle.macros.syntax.lens.*
import java.time.Duration
import java.util.UUID

// TODO(#14568) Merge this into ExternallySignedPartyOnboardingTest
class ExternalPartySetupProposalIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  override val loggerFactory: SuppressingLogger = SuppressingLogger(getClass)

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms(
        // set renewal duration to be same as pre-approval lifetime to ensure renewal
        // gets triggered immediately
        (_, config) =>
          ConfigTransforms.updateAllValidatorConfigs_(
            _.focus(_.transferPreapproval)
              .modify(c => c.copy(renewalDuration = c.preapprovalLifetime))
          )(config),
        // Disable renewal trigger till required in the test
        (_, config) =>
          ConfigTransforms.updateAutomationConfig(ConfigTransforms.ConfigurableApp.Validator)(
            _.withPausedTrigger[RenewTransferPreapprovalTrigger]
          )(config),
        (_, config) =>
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
              .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
          )(config),
        (_, config) =>
          ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
            _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
          )(config),
      )

  }

  "createExternalPartySetupProposal fails if the validator has insufficient funds" in {
    implicit env =>
      val OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
      assertThrowsAndLogsCommandFailures(
        aliceValidatorBackend.createExternalPartySetupProposal(party),
        _.errorMessage should include regex ("400 Bad Request .* Insufficient funds"),
      )
  }

  "createExternalPartySetupProposal fails if a proposal already exists" in { implicit env =>
    val OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
    aliceValidatorWalletClient.tap(10.0)
    aliceValidatorBackend.createExternalPartySetupProposal(party)
    assertThrowsAndLogsCommandFailures(
      aliceValidatorBackend.createExternalPartySetupProposal(party),
      _.errorMessage should include regex ("409 Conflict .* ExternalPartySetupProposal contract already exists"),
    )
  }

  "createExternalPartySetupProposal fails if a preapproval already exists" in { implicit env =>
    val onboarding @ OnboardingResult(party, _, _) = onboardExternalParty(aliceValidatorBackend)
    aliceValidatorWalletClient.tap(10.0)
    createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding)
    assertThrowsAndLogsCommandFailures(
      aliceValidatorBackend.createExternalPartySetupProposal(party),
      _.errorMessage should include regex ("409 Conflict .* TransferPreapproval contract already exists"),
    )
  }

  "listExternalPartySetupProposals returns an empty array if no contracts exist" in {
    implicit env =>
      aliceValidatorBackend
        .listExternalPartySetupProposals() shouldBe empty
  }

  "listTransferPreapprovals returns an empty array if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .listTransferPreapprovals() shouldBe empty
  }

  "lookupTransferPreapprovalByParty returns None if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .lookupTransferPreapprovalByParty(aliceValidatorBackend.getValidatorPartyId()) shouldBe None
  }

  "TransferPreapproval allows to transfer between externally signed parties" in { implicit env =>
    // Onboard and Create/Accept ExternalPartySetupProposal for Alice
    val onboardingAlice @ OnboardingResult(aliceParty, alicePublicKey, alicePrivateKey) =
      onboardExternalParty(aliceValidatorBackend)
    aliceValidatorBackend.participantClient.parties
      .hosted(filterParty = aliceParty.filterString) should not be empty
    aliceValidatorWalletClient.tap(50.0)
    createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, onboardingAlice)
    eventually() {
      aliceValidatorBackend.lookupTransferPreapprovalByParty(aliceParty) should not be empty
      sv1ScanBackend.lookupTransferPreapprovalByParty(aliceParty) should not be empty
    }

    // Transfer 40.0 to Alice
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
    val (cidBob, _) =
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, onboardingBob)
    eventually() {
      aliceValidatorBackend.lookupTransferPreapprovalByParty(bobParty) should not be empty
      sv1ScanBackend.lookupTransferPreapprovalByParty(bobParty) should not be empty
    }
    aliceValidatorBackend
      .listTransferPreapprovals()
      .map(tp => tp.contract.contractId) contains cidBob

    // Transfer 10.0 from Alice to Bob (with OutputFees: 6.1, SenderChangeFee: 6.0)
    val trackingId = UUID.randomUUID().toString
    val prepareSend =
      aliceValidatorBackend.prepareTransferPreapprovalSend(
        aliceParty,
        bobParty,
        BigDecimal(10.0),
        CantonTimestamp.now().plus(Duration.ofHours(24)),
        trackingId,
        0L,
      )
    val (updateId, _) = actAndCheck(
      "Submit signed TransferCommand creation",
      aliceValidatorBackend.submitTransferPreapprovalSend(
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
      ),
    )(
      "DSO automation completes transfer",
      _ => {
        aliceValidatorBackend
          .getExternalPartyBalance(aliceParty)
          .totalUnlockedCoin shouldBe "17.9000000000"
        aliceValidatorBackend
          .getExternalPartyBalance(bobParty)
          .totalUnlockedCoin shouldBe "10.0000000000"
      },
    )
    val update = eventuallySucceeds() {
      sv1ScanBackend.getUpdate(updateId)
    }
    val rewardRound =
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(amuletCodegen.ValidatorRewardCoupon.COMPANION)(
          aliceParty,
          c => c.data.user == aliceParty.toProtoPrimitive,
        )
        .loneElement
        .data
        .round
        .number

    actAndCheck(
      s"Advance rounds until $rewardRound is issuing", {
        advanceRoundsByOneTickViaAutomation()
        advanceRoundsByOneTickViaAutomation()
        advanceRoundsByOneTickViaAutomation()
      },
    )(
      s"Round $rewardRound is issuing",
      _ => {
        val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        issuingRounds.map(_.payload.round.number) should contain(rewardRound)
      },
    )
    clue("ValidatorRewardCoupon gets collected") {
      eventually() {
        // Just checking for archival. Checking the tx history or something for collection is a bit annoying since there
        // are multiple things resulting in validator rewards and we only get a total sum.
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(amuletCodegen.ValidatorRewardCoupon.COMPANION)(
            aliceParty,
            c => c.data.user == aliceParty.toProtoPrimitive,
          ) shouldBe empty

        // Sanity check that the reward really got collected and not expired.
        val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        issuingRounds.map(_.payload.round.number) should contain(rewardRound)
      }
    }

    inside(update) {
      case definitions.UpdateHistoryItem.members.UpdateHistoryTransaction(transaction) =>
        forExactly(1, transaction.eventsById) {
          case (_, definitions.TreeEvent.members.ExercisedEvent(ev)) =>
            ev.choice shouldBe "ExternalPartyAmuletRules_CreateTransferCommand"
          case _ => fail()
        }
    }
  }

  "TransferPreapprovals get renewed by validator automation" in { implicit env =>
    val onboarding = onboardExternalParty(aliceValidatorBackend)
    aliceValidatorWalletClient.tap(10.0)
    val (_, initial) = actAndCheck(
      s"Setup external party ${onboarding.party} on alice validator",
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding),
    )(
      s"TransferPreapproval for external party ${onboarding.party} was created",
      { _ =>
        val preapproval =
          aliceValidatorBackend.lookupTransferPreapprovalByParty(onboarding.party).value
        preapproval.payload.lastRenewedAt should be(preapproval.payload.validFrom)
        preapproval
      },
    )

    def renewalTrigger =
      aliceValidatorBackend.validatorAutomation.trigger[RenewTransferPreapprovalTrigger]
    // Trigger renewal
    setTriggersWithin(Seq.empty, triggersToResumeAtStart = Seq(renewalTrigger)) {
      eventually() {
        val renewed = aliceValidatorBackend.lookupTransferPreapprovalByParty(onboarding.party).value
        renewed.contractId should not be initial.contractId
        renewed.payload.lastRenewedAt should not be renewed.payload.validFrom
        renewed.payload.expiresAt should be(
          initial.payload.expiresAt.plus(
            aliceValidatorBackend.config.transferPreapproval.preapprovalLifetime.asJava
          )
        )
      }
    }
  }
}
