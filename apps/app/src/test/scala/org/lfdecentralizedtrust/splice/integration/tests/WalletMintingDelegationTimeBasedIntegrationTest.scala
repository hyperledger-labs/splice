// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  AppRewardCoupon,
  UnclaimedActivityRecord,
  ValidatorRewardCoupon,
  ValidatorRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.IssuingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLivenessActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation as mintingDelegationCodegen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.wallet.automation.{
  CollectRewardsAndMergeAmuletsTrigger,
  RejectInvalidMintingDelegationProposalTrigger,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.store.Limit
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, TriggerTestUtil, WalletTestUtil}
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletMintingDelegationTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  private val DefaultAmuletMergeLimit = 10

  // We create many coupons directly, so avoid running sanity checks
  override protected def runUpdateHistorySanityCheck: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withTrafficTopupsDisabled

  "Wallet MintingDelegation APIs" should {
    "allow validator to list, accept, and reject minting delegation proposals and delegations" in {
      implicit env =>
        val validatorParty = aliceValidatorBackend.getValidatorPartyId()

        aliceValidatorWalletClient.tap(100.0)

        val beneficiaryParty = onboardExternalParty(aliceValidatorBackend, Some("beneficiary"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryParty)

        // Use a separate party to test that its proposals/delegation remain
        // unaffected when modifying beneficiaryParty's proposals/delegations
        val beneficiary2Party =
          onboardExternalParty(aliceValidatorBackend, Some("beneficiary2"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary2Party)

        // Verify initial state
        aliceValidatorWalletClient.listMintingDelegationProposals().proposals shouldBe empty
        aliceValidatorWalletClient.listMintingDelegations().delegations shouldBe empty

        val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
        // Init setup: create a delegation + proposal for beneficiary2Party
        val (_, proposal0Cid) = actAndCheck(
          "Create minting delegation proposal for beneficiary2",
          createMintingDelegationProposal(beneficiary2Party, validatorParty, expiresAt),
        )(
          "Proposal is visible to validator",
          _ => {
            val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 1
            proposals.proposals.head.contractId
          },
        )

        actAndCheck(
          "Accept proposal and create delegation for beneficiary2",
          aliceValidatorWalletClient.acceptMintingDelegationProposal(proposal0Cid),
        )(
          "Delegation is created",
          _ => {
            val delegations = aliceValidatorWalletClient.listMintingDelegations()
            delegations.delegations should have size 1
          },
        )

        actAndCheck(
          "Create another proposal for beneficiary2",
          createMintingDelegationProposal(beneficiary2Party, validatorParty, expiresAt),
        )(
          "Proposal is visible to validator",
          _ => {
            val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 1
          },
        )

        // Test 1: Creates a proposal and test reject
        val (_, proposal1Cid) = actAndCheck(
          "Create minting delegation proposal",
          createMintingDelegationProposal(beneficiaryParty, validatorParty, expiresAt),
        )(
          "Proposal is visible to validator",
          _ => {
            val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 2
            proposals.proposals
              .find(
                _.payload.hcursor
                  .downField("delegation")
                  .get[String]("beneficiary")
                  .contains(beneficiaryParty.party.toProtoPrimitive)
              )
              .value
              .contractId
          },
        )

        actAndCheck(
          "Validator rejects the proposal",
          aliceValidatorWalletClient.rejectMintingDelegationProposal(proposal1Cid),
        )(
          "Rejected proposal disappears from list",
          _ =>
            aliceValidatorWalletClient.listMintingDelegationProposals().proposals should have size 1,
        )

        // Test 2: Create a second proposal and test accept
        val (_, proposal2Cid) = actAndCheck(
          "Create minting delegation proposal",
          createMintingDelegationProposal(beneficiaryParty, validatorParty, expiresAt),
        )(
          "Proposal is visible to validator",
          _ => {
            val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 2
            proposals.proposals
              .find(
                _.payload.hcursor
                  .downField("delegation")
                  .get[String]("beneficiary")
                  .contains(beneficiaryParty.party.toProtoPrimitive)
              )
              .value
              .contractId
          },
        )

        val (delegationCid, _) = actAndCheck(
          "Validator accepts the proposal",
          aliceValidatorWalletClient.acceptMintingDelegationProposal(proposal2Cid),
        )(
          "Proposal is archived and delegation is created",
          delegationCid => {
            aliceValidatorWalletClient.listMintingDelegationProposals().proposals should have size 1
            val delegations = aliceValidatorWalletClient.listMintingDelegations()
            delegations.delegations should have size 2
            delegationCid
          },
        )

        // Test 3: Create a new proposal and confirm that accepting it archives existing delegation
        val (_, proposal3Cid) = actAndCheck(
          "Create minting delegation proposal",
          createMintingDelegationProposal(beneficiaryParty, validatorParty, expiresAt),
        )(
          "Proposal is visible to validator",
          _ => {
            val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 2
            proposals.proposals
              .find(
                _.payload.hcursor
                  .downField("delegation")
                  .get[String]("beneficiary")
                  .contains(beneficiaryParty.party.toProtoPrimitive)
              )
              .value
              .contractId
          },
        )

        val (newDelegationCid, _) = actAndCheck(
          "Validator accepts new proposal",
          aliceValidatorWalletClient.acceptMintingDelegationProposal(proposal3Cid),
        )(
          "Old delegation is archived, only the new delegation exists",
          newDelegationCid => {
            aliceValidatorWalletClient.listMintingDelegationProposals().proposals should have size 1
            val delegations = aliceValidatorWalletClient.listMintingDelegations()
            delegations.delegations should have size 2
            val beneficiaryDelegation = delegations.delegations
              .find(
                _.payload.hcursor
                  .get[String]("beneficiary")
                  .contains(beneficiaryParty.party.toProtoPrimitive)
              )
              .value
            beneficiaryDelegation.contractId shouldBe newDelegationCid
            newDelegationCid
          },
        )
    }
  }

  "RejectInvalidMintingDelegationProposalTrigger" should {
    "auto reject invalid minting delegation proposals" in { implicit env =>
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceValidatorWalletClient.tap(100.0)

      val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant

      val validatorWalletRejectTrigger = rejectInvalidMintingDelegationProposalTrigger(
        aliceValidatorBackend,
        aliceValidatorWalletClient.config.ledgerApiUser,
      )

      clue("Auto-reject proposals from non-onboarded external parties") {
        // Note: We intentionally skip createAndAcceptExternalPartySetupProposal
        // to ensure that this party is not fully onboarded
        val nonOnboardedParty = onboardExternalParty(aliceValidatorBackend, Some("nonOnboarded"))

        aliceValidatorWalletClient.listMintingDelegationProposals().proposals shouldBe empty

        setTriggersWithin(triggersToPauseAtStart = Seq(validatorWalletRejectTrigger)) {
          actAndCheck(
            "Create proposal",
            createMintingDelegationProposal(nonOnboardedParty, aliceValidatorParty, expiresAt),
          )(
            "Proposal should be visible while trigger is paused",
            _ =>
              aliceValidatorWalletClient
                .listMintingDelegationProposals()
                .proposals should have size 1,
          )
        }

        clue("Proposal should be auto-rejected because beneficiary is not onboarded") {
          eventually() {
            aliceValidatorWalletClient.listMintingDelegationProposals().proposals shouldBe empty
          }
        }
      }

      val beneficiaryParty =
        onboardExternalParty(aliceValidatorBackend, Some("beneficiary"))
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryParty)

      clue("Auto-reject proposals when delegate is not a validator") {
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        val aliceWalletRejectTrigger = rejectInvalidMintingDelegationProposalTrigger(
          aliceValidatorBackend,
          aliceWalletClient.config.ledgerApiUser,
        )

        setTriggersWithin(triggersToPauseAtStart = Seq(aliceWalletRejectTrigger)) {
          actAndCheck(
            "Create proposal",
            createMintingDelegationProposal(beneficiaryParty, aliceParty, expiresAt),
          )(
            "Proposal should be visible while trigger is paused",
            _ => aliceWalletClient.listMintingDelegationProposals().proposals should have size 1,
          )
        }

        clue("Proposal should be auto-rejected because delegate is not the validator") {
          eventually() {
            aliceWalletClient.listMintingDelegationProposals().proposals shouldBe empty
          }
        }
      }

      clue("Auto-reject duplicate proposals from same beneficiary") {
        val now = env.environment.clock.now
        val expiresAt1 = now.plus(Duration.ofDays(10)).toInstant
        val expiresAt2 = now.plus(Duration.ofDays(30)).toInstant
        val expiresAt3 = now.plus(Duration.ofDays(20)).toInstant

        setTriggersWithin(triggersToPauseAtStart = Seq(validatorWalletRejectTrigger)) {
          actAndCheck(
            "Create three proposals", {
              createMintingDelegationProposal(beneficiaryParty, aliceValidatorParty, expiresAt1)
              createMintingDelegationProposal(beneficiaryParty, aliceValidatorParty, expiresAt2)
              createMintingDelegationProposal(beneficiaryParty, aliceValidatorParty, expiresAt3)
            },
          )(
            "All three proposals should be visible while trigger is paused",
            _ =>
              aliceValidatorWalletClient
                .listMintingDelegationProposals()
                .proposals should have size 3,
          )
        }

        clue("Trigger should reject duplicates, keeping only one proposal") {
          eventually() {
            aliceValidatorWalletClient
              .listMintingDelegationProposals()
              .proposals should have size 1
          }
        }
      }
    }
  }

  "MintingDelegationCollectRewardsTrigger" should {
    "collect rewards for all coupons owned by the beneficiary" in { implicit env =>
      // This test verifies that MintingDelegationCollectRewardsTrigger collects
      // ValidatorRewardCoupons, AppRewardCoupons, ValidatorLivenessActivityRecords,
      // and UnclaimedActivityRecords.

      // setup: create delegation for external-party
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val validatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceValidatorWalletClient.tap(100.0)

      val beneficiaryParty =
        onboardExternalParty(aliceValidatorBackend, Some("coupon_beneficiary"))
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryParty)

      val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
      val (_, proposalContractId) = actAndCheck(
        "Create minting delegation proposal",
        createMintingDelegationProposal(beneficiaryParty, validatorParty, expiresAt),
      )(
        "Proposal is visible",
        _ => {
          val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
          proposals.proposals should have size 1
          proposals.proposals.head.contractId
        },
      )

      // The accept sometimes fails due to race with rejectInvalidMintingDelegationProposalTrigger
      eventually() {
        aliceValidatorWalletClient.acceptMintingDelegationProposal(proposalContractId)
      }

      val externalPartyWallet = eventually() {
        aliceValidatorBackend.appState.walletManager
          .valueOrFail("WalletManager is expected to be defined")
          .externalPartyWalletManager
          .lookupExternalPartyWallet(beneficiaryParty.party)
          .valueOrFail(
            s"Expected ${beneficiaryParty.party} to have an external party wallet"
          )
      }

      // helpful APIs
      def getBalance(): BigDecimal = BigDecimal(
        aliceValidatorBackend
          .getExternalPartyBalance(beneficiaryParty.party)
          .totalUnlockedCoin
      )

      def advanceAndGetIssuingRound(): IssuingMiningRound = {
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        eventually() {
          val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          issuingRounds.toList.lastOption.value.payload
        }
      }

      def advanceRoundsAndVerifyBalanceIncrease(): Unit = {
        val balanceBefore = getBalance()

        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening
        advanceTimeForRewardAutomationToRunForCurrentRound

        val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val issuingRoundsMap = issuingRounds.view.map(r => r.payload.round -> r.payload).toMap

        clue("All reward contracts should be consumed") {
          eventually() {
            externalPartyWallet.store.listSortedValidatorRewards(None).futureValue shouldBe empty
            externalPartyWallet.store.listUnclaimedActivityRecords().futureValue shouldBe empty
            externalPartyWallet.store
              .listSortedAppRewards(issuingRoundsMap)
              .futureValue shouldBe empty
            externalPartyWallet.store
              .listSortedLivenessActivityRecords(issuingRoundsMap)
              .futureValue shouldBe empty
          }
        }

        clue("Balance should have increased") {
          eventually() {
            val balanceAfter = getBalance()
            balanceAfter should be > balanceBefore
          }
        }
      }

      // Tests start -----

      clue("Test AppRewardCoupon") {
        val issuingRound = advanceAndGetIssuingRound()

        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(dsoParty),
            readAs = Seq.empty,
            update = new AppRewardCoupon(
              dsoParty.toProtoPrimitive,
              beneficiaryParty.party.toProtoPrimitive,
              false, // not featured
              BigDecimal(100.0).bigDecimal,
              issuingRound.round,
              java.util.Optional.empty(),
            ).create,
          )

        advanceRoundsAndVerifyBalanceIncrease()
      }

      clue("Test ValidatorLivenessActivityRecord") {
        val issuingRound = advanceAndGetIssuingRound()

        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(dsoParty),
            readAs = Seq.empty,
            update = new ValidatorLivenessActivityRecord(
              dsoParty.toProtoPrimitive,
              beneficiaryParty.party.toProtoPrimitive,
              issuingRound.round,
            ).create,
          )

        advanceRoundsAndVerifyBalanceIncrease()
      }

      clue("Test UnclaimedActivityRecord") {
        sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitWithResult(
            userId = sv1Backend.config.ledgerApiUser,
            actAs = Seq(dsoParty),
            readAs = Seq.empty,
            update = new UnclaimedActivityRecord(
              dsoParty.toProtoPrimitive,
              beneficiaryParty.party.toProtoPrimitive,
              BigDecimal(100.0).bigDecimal,
              "test reward",
              env.environment.clock.now.plus(Duration.ofDays(1)).toInstant,
            ).create,
          )

        advanceRoundsAndVerifyBalanceIncrease()
      }

      // For ValidatorRewardCoupon, we need ValidatorRight for beneficiary
      // And also need to pause the validator's own reward collection
      // which would normally mint this coupon
      clue("Test ValidatorRewardCoupon") {
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJavaExternalOrLocal(
            actingParty = beneficiaryParty.richPartyId,
            commands = new ValidatorRight(
              dsoParty.toProtoPrimitive,
              beneficiaryParty.party.toProtoPrimitive,
              beneficiaryParty.party.toProtoPrimitive, // validator = beneficiary
            ).create.commands.asScala.toSeq,
          )

        val issuingRound4 = advanceAndGetIssuingRound()

        // Pause the validator's reward collection to prevent race
        val validatorRewardTrigger = collectRewardsAndMergeAmuletsTrigger(
          aliceValidatorBackend,
          aliceValidatorWalletClient.config.ledgerApiUser,
        )

        setTriggersWithin(triggersToPauseAtStart = Seq(validatorRewardTrigger)) {
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = sv1Backend.config.ledgerApiUser,
              actAs = Seq(dsoParty),
              readAs = Seq.empty,
              update = new ValidatorRewardCoupon(
                dsoParty.toProtoPrimitive,
                beneficiaryParty.party.toProtoPrimitive,
                BigDecimal(100.0).bigDecimal,
                issuingRound4.round,
              ).create,
            )

          advanceRoundsAndVerifyBalanceIncrease()
        }
      }
    }

    "merge amulets above when they go above the merge-limit" in { implicit env =>
      val validatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceValidatorWalletClient.tap(100.0)

      // Onboard external party
      val beneficiaryParty =
        onboardExternalParty(aliceValidatorBackend, Some("merge_beneficiary"))
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryParty)

      // Create MintingDelegation for the external party with a smaller merge limit
      val amuletMergeLimit = 2
      val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
      val (_, proposalContractId) = actAndCheck(
        "Create minting delegation proposal",
        createMintingDelegationProposalWithMergeLimit(
          beneficiaryParty,
          validatorParty,
          expiresAt,
          amuletMergeLimit,
        ),
      )(
        "Proposal is visible",
        _ => {
          val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
          proposals.proposals should have size 1
          proposals.proposals.head.contractId
        },
      )

      // The accept sometimes fails due to race with rejectInvalidMintingDelegationProposalTrigger
      eventually() {
        aliceValidatorWalletClient.acceptMintingDelegationProposal(proposalContractId)
      }

      eventually() {
        val delegations = aliceValidatorWalletClient.listMintingDelegations()
        delegations.delegations should have size 1
      }

      // Transfer a few amulets to the external party
      // The MintingDelegationCollectRewardsTrigger should merge them
      (1 to amuletMergeLimit + 2).foreach { i =>
        aliceValidatorWalletClient.transferPreapprovalSend(
          beneficiaryParty.party,
          10.0,
          s"transfer-$i",
        )
      }

      advanceRoundsToNextRoundOpening

      val externalPartyWallet = eventually() {
        aliceValidatorBackend.appState.walletManager
          .valueOrFail("WalletManager is expected to be defined")
          .externalPartyWalletManager
          .lookupExternalPartyWallet(beneficiaryParty.party)
          .valueOrFail(
            s"Expected ${beneficiaryParty.party} to have an external party wallet"
          )
      }

      clue("Verify amulets were merged to exactly the merge limit") {
        eventually() {
          val amulets = externalPartyWallet.store.multiDomainAcsStore
            .listContracts(Amulet.COMPANION, Limit.DefaultLimit)
            .futureValue
          amulets should have size amuletMergeLimit.toLong
        }
      }

      // Also test the scenario when we are at merge limit and have rewards to collect
      val issuingRound = eventually() {
        val rounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
        rounds should not be empty
        rounds.last
      }

      sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitWithResult(
          userId = sv1Backend.config.ledgerApiUser,
          actAs = Seq(dsoParty),
          readAs = Seq.empty,
          update = new AppRewardCoupon(
            dsoParty.toProtoPrimitive,
            beneficiaryParty.party.toProtoPrimitive,
            false, // not featured
            BigDecimal(1.0).bigDecimal,
            issuingRound.payload.round,
            java.util.Optional.empty(),
          ).create,
        )

      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening
      advanceTimeForRewardAutomationToRunForCurrentRound

      clue("Verify amulets count is still at merge-limit after collecting rewards") {
        eventually() {
          val amulets = externalPartyWallet.store.multiDomainAcsStore
            .listContracts(Amulet.COMPANION, Limit.DefaultLimit)
            .futureValue
          amulets should have size amuletMergeLimit.toLong
        }
      }
    }
  }

  private def rejectInvalidMintingDelegationProposalTrigger(
      validatorBackend: ValidatorAppBackendReference,
      userName: String,
  ): Trigger =
    validatorBackend
      .userWalletAutomation(userName)
      .futureValue
      .trigger[RejectInvalidMintingDelegationProposalTrigger]

  private def collectRewardsAndMergeAmuletsTrigger(
      validatorBackend: ValidatorAppBackendReference,
      userName: String,
  ): Trigger =
    validatorBackend
      .userWalletAutomation(userName)
      .futureValue
      .trigger[CollectRewardsAndMergeAmuletsTrigger]

  private def createMintingDelegationProposal(
      beneficiaryParty: OnboardingResult,
      delegate: PartyId,
      expiresAt: java.time.Instant,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    createMintingDelegationProposalWithMergeLimit(
      beneficiaryParty,
      delegate,
      expiresAt,
      DefaultAmuletMergeLimit,
    )
  }

  private def createMintingDelegationProposalWithMergeLimit(
      beneficiaryParty: OnboardingResult,
      delegate: PartyId,
      expiresAt: java.time.Instant,
      amuletMergeLimit: Int,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val beneficiary = beneficiaryParty.party
    val proposal = new mintingDelegationCodegen.MintingDelegationProposal(
      new mintingDelegationCodegen.MintingDelegation(
        beneficiary.toProtoPrimitive,
        delegate.toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        expiresAt,
        amuletMergeLimit.toLong,
      )
    )
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        actingParty = beneficiaryParty.richPartyId,
        commands = proposal.create.commands.asScala.toSeq,
      )
  }
}
