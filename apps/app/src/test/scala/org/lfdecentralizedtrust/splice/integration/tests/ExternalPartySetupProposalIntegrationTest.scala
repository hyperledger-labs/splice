package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet as amuletCodegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  ExternalPartySetupProposal,
  TransferPreapproval,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_CreateExternalPartySetupProposal
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperationoutcome.COO_CreateExternalPartySetupProposal
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.{
  AmuletOperation,
  WalletAppInstall,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
  ExpireTransferPreapprovalsTrigger,
}
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.validator.automation.RenewTransferPreapprovalTrigger
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.topology.admin.grpc.TopologyStoreId
import monocle.macros.syntax.lens.*

import java.time.Instant
import java.util.UUID
import scala.annotation.nowarn
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

// TODO(DACH-NY/canton-network-node#14568) Merge this into ExternallySignedPartyOnboardingTest
class ExternalPartySetupProposalIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  override lazy val sanityChecksIgnoredRootExercises = Seq(
    (TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID, "Archive")
  )

  override lazy val sanityChecksIgnoredRootCreates = Seq(
    TransferPreapproval.TEMPLATE_ID_WITH_PACKAGE_ID,
    amuletCodegen.AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    amuletCodegen.ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  // Set above transferPreapprovalLifetimeDuration to be able to test out of funds
  val preapprovalLifetime = NonNegativeFiniteDuration.ofDays(100)

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // Uses FeaturedAppMarkers: test asserts on AppRewardCoupon which TBAR replaces with RewardCouponV2
      .addConfigTransform((_, config) => ConfigTransforms.withFeaturedAppMarkers(config))
      .addConfigTransforms(
        // set renewal duration to be same as pre-approval lifetime to ensure renewal
        // gets triggered immediately
        (_, config) =>
          ConfigTransforms.updateAllValidatorConfigs_(
            _.focus(_.transferPreapproval)
              .modify(c =>
                c.copy(
                  renewalDuration = preapprovalLifetime,
                  preapprovalLifetime = preapprovalLifetime,
                )
              )
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
          ConfigTransforms.updateInitialTickDuration(
            NonNegativeFiniteDuration.ofMillis(500)
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
        .listExternalPartySetupProposals() shouldBe empty withClue "ExternalPartySetupProposals"
  }

  "listTransferPreapprovals returns an empty array if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .listTransferPreapprovals() shouldBe empty withClue "TransferPreapprovals"
  }

  "lookupTransferPreapprovalByParty returns None if no contracts exist" in { implicit env =>
    aliceValidatorBackend
      .lookupTransferPreapprovalByParty(aliceValidatorBackend.getValidatorPartyId()) shouldBe None
  }

  "TransferPreapproval allows to transfer between externally signed parties" taggedAs (org.lfdecentralizedtrust.splice.util.Tags.SpliceAmulet_0_1_9) in {
    implicit env =>
      // Onboard and Create/Accept ExternalPartySetupProposal for Alice
      val onboardingAlice @ OnboardingResult(aliceParty, alicePublicKey, alicePrivateKey) =
        onboardExternalParty(aliceValidatorBackend, Some("aliceExternal"))
      aliceValidatorBackend.participantClient.parties
        .hosted(filterParty =
          aliceParty.filterString
        ) should not be empty withClue "alice hosted on aliceValidator participant"
      aliceValidatorWalletClient.tap(50.0)
      createAndAcceptExternalPartySetupProposal(
        aliceValidatorBackend,
        onboardingAlice,
        verboseHashing = true,
      )
      eventually() {
        aliceValidatorBackend.lookupTransferPreapprovalByParty(
          aliceParty
        ) should not be empty withClue "TransferPreapprovals from validator"
        aliceValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(
          aliceParty
        ) should not be empty withClue "TransferPreapprovals from scan-proxy"
      }

      // Transfer 2000.0 to Alice
      aliceValidatorBackend
        .getExternalPartyBalance(aliceParty)
        .totalUnlockedCoin shouldBe "0.0000000000"
      aliceValidatorWalletClient.transferPreapprovalSend(
        aliceParty,
        2000.0,
        UUID.randomUUID.toString,
      )
      eventually() {
        aliceValidatorBackend
          .getExternalPartyBalance(aliceParty)
          .totalUnlockedCoin shouldBe "2000.0000000000"
      }

      // Onboard and Create/Accept ExternalPartySetupProposal for Bob
      val onboardingBob @ OnboardingResult(bobParty, _, _) =
        onboardExternalParty(bobValidatorBackend, Some("bobExternal"))
      bobValidatorBackend.participantClient.parties
        .hosted(filterParty =
          bobParty.filterString
        ) should not be empty withClue "bob hosted on bobValidator participant"
      bobValidatorWalletClient.tap(50.0)
      val onboardingBobExtPartySetupResult =
        createAndAcceptExternalPartySetupProposal(
          bobValidatorBackend,
          onboardingBob,
          verboseHashing = true,
        )
      eventually() {
        bobValidatorBackend.lookupTransferPreapprovalByParty(
          bobParty
        ) should not be empty withClue "bobValidator TransferPreapprovals"
        bobValidatorBackend.scanProxy.lookupTransferPreapprovalByParty(
          bobParty
        ) should not be empty withClue "bob scan-proxy TransferPreapprovals"
      }
      bobValidatorBackend
        .listTransferPreapprovals()
        .map(tp =>
          tp.contract.contractId
        ) contains onboardingBobExtPartySetupResult.transferPreapprovalCid

      // Lookup transfer command counter before any transfer command
      aliceValidatorBackend.scanProxy.lookupTransferCommandCounterByParty(aliceParty) shouldBe None

      // Lookup transfer command that does not exist
      aliceValidatorBackend.scanProxy.lookupTransferCommandStatus(
        aliceParty,
        0L,
      ) shouldBe None

      val (_, issuingRound) = actAndCheck(
        s"Advance rounds until there is at least one issuing round", {
          advanceRoundsByOneTickViaAutomation()
        },
      )(
        s"There is at least one issuing round",
        _ => {
          val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          issuingRounds.toList.headOption.value.payload
        },
      )

      val appRewardAmount = BigDecimal(10.0)

      actAndCheck(
        s"Create AppRewardCoupon for round ${issuingRound.round} through bare create",
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new amuletCodegen.AppRewardCoupon(
            dsoParty.toProtoPrimitive,
            aliceParty.toProtoPrimitive,
            false,
            appRewardAmount.bigDecimal,
            issuingRound.round,
            java.util.Optional.empty(),
          ).create,
        ),
      )(
        "AppRewardCoupon is visible",
        _ =>
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(amuletCodegen.AppRewardCoupon.COMPANION)(
              aliceParty,
              c => c.data.provider == aliceParty.toProtoPrimitive,
            ) should have length (1),
      )
  }

  "Wallet automation starts for external parties with signing keys embedded in PartyToParticipant" in {
    implicit env =>
      val onboarding = onboardExternalPartyWithEmbeddedKeys(aliceValidatorBackend)
      val synchronizerStore = TopologyStoreId.Synchronizer(decentralizedSynchronizerId)
      eventually() {
        aliceValidatorBackend.participantClient.topology.party_to_participant_mappings
          .list(
            synchronizerStore,
            filterParty = onboarding.party.filterString,
          )
          .loneElement
          .item
          .partySigningKeysWithThreshold should not be empty
      }
      // The point of this test is to cover parties without a PartyToKeyMapping
      @nowarn("cat=deprecation")
      val partyToKeyMappings =
        aliceValidatorBackend.participantClient.topology.party_to_key_mappings.list(
          synchronizerStore,
          filterParty = onboarding.party.filterString,
        )
      partyToKeyMappings shouldBe empty
      aliceValidatorWalletClient.tap(10.0)
      actAndCheck(
        s"Setup external party ${onboarding.party} on alice validator",
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, onboarding),
      )(
        s"Wallet automation for ${onboarding.party} is started",
        _ =>
          aliceValidatorBackend.appState.walletManager
            .valueOrFail("WalletManager is expected to be defined")
            .externalPartyWalletManager
            .lookupExternalPartyWallet(onboarding.party) should not be empty,
      )
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

  // TODO(DACH-NY/canton-network-node#15468): Simplify this test to not require a ledger submission
  "TransferPreapprovals get expired by SV automation" in { implicit env =>
    val onboarding = onboardExternalParty(aliceValidatorBackend)
    val externalParty = onboarding.party
    aliceValidatorWalletClient.tap(10.0)
    aliceValidatorBackend.lookupTransferPreapprovalByParty(externalParty) shouldBe None
    // Pause the expiry trigger
    setTriggersWithin(
      triggersToPauseAtStart =
        env.svs.local.map(_.dsoDelegateBasedAutomation.trigger[ExpireTransferPreapprovalsTrigger])
    ) {
      val (proposalCid, _) = actAndCheck(
        s"Create a proposal to setup an external party with a soon-to-expire transfer preapproval",
        createExternalPartyProposalViaLedgerApi(externalParty, Instant.now().plusSeconds(5)),
      )(
        s"External party setup proposal for $externalParty was created",
        { proposalCid =>
          aliceValidatorBackend
            .listExternalPartySetupProposals()
            .map(c => c.contract.contractId) should contain(proposalCid)
        },
      )
      actAndCheck(
        "External party accepts the proposal",
        acceptExternalPartySetupProposal(aliceValidatorBackend, onboarding, proposalCid),
      )(
        "An expiring TransferPreapproval for the external party is created",
        _ =>
          aliceValidatorBackend.lookupTransferPreapprovalByParty(externalParty) should not be None,
      )
    }

    // Expiry trigger resumed
    clue("SV automation expires the TransferPreapproval contract") {
      eventually() {
        aliceValidatorBackend.lookupTransferPreapprovalByParty(externalParty) shouldBe None
      }
    }
  }

  private def createExternalPartyProposalViaLedgerApi(receiverParty: PartyId, expiresAt: Instant)(
      implicit env: SpliceTestConsoleEnvironment
  ): ExternalPartySetupProposal.ContractId = {
    val validatorParty = aliceValidatorBackend.getValidatorPartyId()
    val transferContext = sv1ScanBackend.getTransferContextWithInstances(env.environment.clock.now)
    val inputAmulets = aliceValidatorWalletClient.list().amulets
    val walletInstall = inside(
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
        .filterJava(WalletAppInstall.COMPANION)(
          validatorParty,
          c => c.data.validatorParty == c.data.endUserParty,
        )
    ) { case Seq(install) => install }
    val executeBatchCmd = walletInstall.id.exerciseWalletAppInstall_ExecuteBatch(
      new splice.amuletrules.PaymentTransferContext(
        transferContext.amuletRules.contract.contractId,
        new splice.amuletrules.TransferContext(
          transferContext.latestOpenMiningRound.contract.contractId,
          Map.empty[Round, IssuingMiningRound.ContractId].asJava,
          Map.empty[String, splice.amulet.ValidatorRight.ContractId].asJava,
          None.toJava,
        ),
      ),
      inputAmulets
        .map(_.contract.contractId.contractId)
        .map[splice.amuletrules.TransferInput](cid =>
          new splice.amuletrules.transferinput.InputAmulet(new splice.amulet.Amulet.ContractId(cid))
        )
        .asJava,
      List[AmuletOperation](
        new CO_CreateExternalPartySetupProposal(
          receiverParty.toProtoPrimitive,
          expiresAt,
        )
      ).asJava,
    )
    inside(
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          aliceValidatorBackend.config.ledgerApiUser,
          Seq(validatorParty),
          Seq(validatorParty),
          executeBatchCmd,
          disclosedContracts = DisclosedContracts
            .forTesting(
              transferContext.amuletRules,
              transferContext.latestOpenMiningRound,
            )
            .toLedgerApiDisclosedContracts,
        )
        .exerciseResult
        .outcomes
        .asScala
        .toSeq
    ) { case Seq(outcome) =>
      outcome.asInstanceOf[COO_CreateExternalPartySetupProposal].contractIdValue
    }
  }
}
