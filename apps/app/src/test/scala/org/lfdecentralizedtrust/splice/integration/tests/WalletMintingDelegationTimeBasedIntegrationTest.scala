// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  AppRewardCoupon,
  DevelopmentFundCoupon,
  UnclaimedActivityRecord,
  ValidatorRewardCoupon,
  ValidatorRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLivenessActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation as mintingDelegationCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.wallet.automation.{
  CollectRewardsAndMergeAmuletsTrigger,
  MintingDelegationCollectRewardsTrigger,
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
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(zeroTransferFees = true)
        )(config)
      )

  "Wallet MintingDelegation APIs" should {
    "allow validator to list, accept, and reject minting delegation proposals and delegations" in {
      implicit env =>
        val validatorParty = aliceValidatorBackend.getValidatorPartyId()

        aliceValidatorWalletClient.tap(100.0)

        val beneficiaryParty = onboardExternalParty(aliceValidatorBackend, Some("beneficiary"))

        val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant

        // Use a separate party to test that its proposals/delegation remain
        // unaffected when modifying beneficiaryParty's proposals/delegations
        clue("Init setup: create a delegation + proposal for beneficiary2Party") {
          val beneficiary2Party =
            onboardExternalParty(aliceValidatorBackend, Some("beneficiary2"))
          createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary2Party)

          // Verify initial state
          aliceValidatorWalletClient.listMintingDelegationProposals().proposals shouldBe empty
          aliceValidatorWalletClient.listMintingDelegations().delegations shouldBe empty

          val (_, proposal0Cid) = actAndCheck(
            "Create minting delegation proposal for beneficiary2",
            createMintingDelegationProposal(beneficiary2Party, validatorParty, expiresAt),
          )(
            "Proposal is visible to validator",
            _ => {
              val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
              proposals.proposals should have size 1
              proposals.proposals.head.contract.contractId
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
        }

        // Test 1
        clue("Test beneficiaryHosted status") {
          val (_, proposalBeforeOnboardingCid) = actAndCheck(
            "Create minting delegation proposal before beneficiary is hosted",
            createMintingDelegationProposal(beneficiaryParty, validatorParty, expiresAt),
          )(
            "Proposal is visible with beneficiaryHosted = false",
            _ => {
              val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
              proposals.proposals should have size 2
              val beneficiaryProposal = proposals.proposals
                .find(
                  _.contract.payload.hcursor
                    .downField("delegation")
                    .get[String]("beneficiary")
                    .contains(beneficiaryParty.party.toProtoPrimitive)
                )
                .value
              beneficiaryProposal.beneficiaryHosted shouldBe false
              beneficiaryProposal.contract.contractId
            },
          )

          // Accept the proposal before hosting and verify beneficiaryHosted = false in delegations
          actAndCheck(
            "Accept proposal before beneficiary is hosted",
            aliceValidatorWalletClient.acceptMintingDelegationProposal(proposalBeforeOnboardingCid),
          )(
            "Delegation is visible with beneficiaryHosted = false",
            _ => {
              val delegations = aliceValidatorWalletClient.listMintingDelegations()
              delegations.delegations should have size 2
              val beneficiaryDelegation = delegations.delegations
                .find(
                  _.contract.payload.hcursor
                    .get[String]("beneficiary")
                    .contains(beneficiaryParty.party.toProtoPrimitive)
                )
                .value
              beneficiaryDelegation.beneficiaryHosted shouldBe false
            },
          )
        }

        // Onboard beneficiary
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryParty)

        clue("After hosting, beneficiaryHosted should be true in delegations") {
          val delegations = aliceValidatorWalletClient.listMintingDelegations()
          val beneficiaryDelegation = delegations.delegations
            .find(
              _.contract.payload.hcursor
                .get[String]("beneficiary")
                .contains(beneficiaryParty.party.toProtoPrimitive)
            )
            .value
          beneficiaryDelegation.beneficiaryHosted shouldBe true
        }

        // Test 2: Creates a proposal and test reject
        clue("Test reject minting delegation proposal") {
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
                  _.contract.payload.hcursor
                    .downField("delegation")
                    .get[String]("beneficiary")
                    .contains(beneficiaryParty.party.toProtoPrimitive)
                )
                .value
                .contract
                .contractId
            },
          )

          actAndCheck(
            "Validator rejects the proposal",
            aliceValidatorWalletClient.rejectMintingDelegationProposal(proposal1Cid),
          )(
            "Rejected proposal disappears from list",
            _ =>
              aliceValidatorWalletClient
                .listMintingDelegationProposals()
                .proposals should have size 1,
          )
        }

        // Test 3: Create a second proposal and test accept
        clue("Test accept minting delegation proposal") {
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
                  _.contract.payload.hcursor
                    .downField("delegation")
                    .get[String]("beneficiary")
                    .contains(beneficiaryParty.party.toProtoPrimitive)
                )
                .value
                .contract
                .contractId
            },
          )

          val (delegationCid, _) = actAndCheck(
            "Validator accepts the proposal",
            aliceValidatorWalletClient.acceptMintingDelegationProposal(proposal2Cid),
          )(
            "Proposal is archived and delegation is created",
            delegationCid => {
              aliceValidatorWalletClient
                .listMintingDelegationProposals()
                .proposals should have size 1
              val delegations = aliceValidatorWalletClient.listMintingDelegations()
              delegations.delegations should have size 2
              delegationCid
            },
          )
        }

        // Test 4: Create a new proposal and confirm that accepting it archives existing delegation
        clue("Test accepting new proposal archives existing delegation") {
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
                  _.contract.payload.hcursor
                    .downField("delegation")
                    .get[String]("beneficiary")
                    .contains(beneficiaryParty.party.toProtoPrimitive)
                )
                .value
                .contract
                .contractId
            },
          )

          val (newDelegationCid, _) = actAndCheck(
            "Validator accepts new proposal",
            aliceValidatorWalletClient.acceptMintingDelegationProposal(proposal3Cid),
          )(
            "Old delegation is archived, only the new delegation exists",
            newDelegationCid => {
              aliceValidatorWalletClient
                .listMintingDelegationProposals()
                .proposals should have size 1
              val delegations = aliceValidatorWalletClient.listMintingDelegations()
              delegations.delegations should have size 2
              val beneficiaryDelegation = delegations.delegations
                .find(
                  _.contract.payload.hcursor
                    .get[String]("beneficiary")
                    .contains(beneficiaryParty.party.toProtoPrimitive)
                )
                .value
              beneficiaryDelegation.contract.contractId shouldBe newDelegationCid
              newDelegationCid
            },
          )
        }

        // Test 4: Test auto-expiry of delegation and proposal
        clue("Test expiry of MintingDelegation and MintingDelegationProposal") {
          val expiresAtOneMin = env.environment.clock.now.plus(Duration.ofMinutes(1)).toInstant

          // Create a third beneficiary for expiry testing
          val beneficiary3Party =
            onboardExternalParty(aliceValidatorBackend, Some("beneficiary3"))
          createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary3Party)

          // Create proposal and accept it to create a delegation
          val (_, proposalCidExpiry) = actAndCheck(
            "Create minting delegation proposal with short expiry",
            createMintingDelegationProposal(beneficiary3Party, validatorParty, expiresAtOneMin),
          )(
            "Proposal is visible",
            _ => {
              val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
              proposals.proposals should have size 2
              proposals.proposals
                .find(
                  _.contract.payload.hcursor
                    .downField("delegation")
                    .get[String]("beneficiary")
                    .contains(beneficiary3Party.party.toProtoPrimitive)
                )
                .value
                .contract
                .contractId
            },
          )

          actAndCheck(
            "Accept proposal to create delegation with short expiry",
            aliceValidatorWalletClient.acceptMintingDelegationProposal(proposalCidExpiry),
          )(
            "Delegation is created",
            _ => {
              val delegations = aliceValidatorWalletClient.listMintingDelegations()
              delegations.delegations should have size 3
            },
          )

          // Create another proposal and leave it unaccepted
          actAndCheck(
            "Create another proposal with short expiry",
            createMintingDelegationProposal(beneficiary3Party, validatorParty, expiresAtOneMin),
          )(
            "Second proposal is visible",
            _ => {
              val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
              proposals.proposals should have size 2
            },
          )

          // Advance time past expiry
          advanceTime(Duration.ofMinutes(2))

          clue("Expired delegation should be auto-rejected") {
            eventually() {
              val delegations = aliceValidatorWalletClient.listMintingDelegations().delegations
              delegations.size shouldBe 2
            }
          }

          clue("Expired proposal should be auto-rejected") {
            eventually() {
              val proposals = aliceValidatorWalletClient.listMintingDelegationProposals().proposals
              proposals should have size 1
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

      // Use alice (regular user) as the delegate
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)

      // Validator also needs funds for the external party setup proposal
      aliceValidatorWalletClient.tap(100.0)

      val beneficiaryParty =
        onboardExternalParty(aliceValidatorBackend, Some("coupon_beneficiary"))
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryParty)

      val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
      val (_, proposalContractId) = actAndCheck(
        "Create minting delegation proposal",
        createMintingDelegationProposal(beneficiaryParty, aliceParty, expiresAt),
      )(
        "Proposal is visible",
        _ => {
          val proposals = aliceWalletClient.listMintingDelegationProposals()
          proposals.proposals should have size 1
          proposals.proposals.head.contract.contractId
        },
      )

      // Alice accepts the proposal (not the validator)
      actAndCheck(
        "Alice accepts the proposal",
        aliceWalletClient.acceptMintingDelegationProposal(proposalContractId),
      )(
        "Delegation is created",
        _ => {
          val delegations = aliceWalletClient.listMintingDelegations()
          delegations.delegations should have size 1
        },
      )

      val externalPartyWallet = eventually() {
        aliceValidatorBackend.appState.walletManager
          .valueOrFail("WalletManager is expected to be defined")
          .externalPartyWalletManager
          .lookupExternalPartyWallet(beneficiaryParty.party)
          .valueOrFail(
            s"Expected ${beneficiaryParty.party} to have an external party wallet"
          )
      }

      def getBalance(): BigDecimal = BigDecimal(
        aliceValidatorBackend
          .getExternalPartyBalance(beneficiaryParty.party)
          .totalUnlockedCoin
      )

      advanceRoundsToNextRoundOpening
      advanceRoundsToNextRoundOpening

      // Get an issuing round whose opensAt is in the past.
      val issuingRound = eventually() {
        val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        issuingRounds.toList.headOption.value.payload
      }

      val balanceBefore = getBalance()
      balanceBefore shouldBe BigDecimal(0)

      val appRewardAmount = BigDecimal(100.0)
      val unclaimedActivityAmount = BigDecimal(200.0)
      val validatorRewardAmount = BigDecimal(500.0)
      val developmentFundAmount = BigDecimal(300.0)

      // For ValidatorRewardCoupon, we need ValidatorRight for beneficiary
      aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
        .submitJavaExternalOrLocal(
          actingParty = beneficiaryParty.richPartyId,
          commands = new ValidatorRight(
            dsoParty.toProtoPrimitive,
            beneficiaryParty.party.toProtoPrimitive,
            beneficiaryParty.party.toProtoPrimitive, // validator = beneficiary
          ).create.commands.asScala.toSeq,
        )

      // Pause the validator's own reward collection trigger which would
      // normally mint this coupon for itself, because the validator-app currently
      // auto creates the ValidatorRight contract while onboarding the external-party
      val validatorRewardTrigger = collectRewardsAndMergeAmuletsTrigger(
        aliceValidatorBackend,
        aliceValidatorWalletClient.config.ledgerApiUser,
      )

      setTriggersWithin(triggersToPauseAtStart = Seq(validatorRewardTrigger)) {
        val externalPartyMintingDelegationTrigger = mintingDelegationCollectRewardsTrigger(
          aliceValidatorBackend,
          beneficiaryParty.party,
        )

        // Pause minting delegation trigger to ensure we mint them together
        setTriggersWithin(triggersToPauseAtStart = Seq(externalPartyMintingDelegationTrigger)) {
          // Create AppRewardCoupon
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = sv1Backend.config.ledgerApiUser,
              actAs = Seq(dsoParty),
              readAs = Seq.empty,
              update = new AppRewardCoupon(
                dsoParty.toProtoPrimitive,
                beneficiaryParty.party.toProtoPrimitive,
                false,
                appRewardAmount.bigDecimal,
                issuingRound.round,
                java.util.Optional.empty(),
              ).create,
            )

          // Create UnclaimedActivityRecord
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = sv1Backend.config.ledgerApiUser,
              actAs = Seq(dsoParty),
              readAs = Seq.empty,
              update = new UnclaimedActivityRecord(
                dsoParty.toProtoPrimitive,
                beneficiaryParty.party.toProtoPrimitive,
                unclaimedActivityAmount.bigDecimal,
                "test reward",
                env.environment.clock.now.plus(Duration.ofDays(1)).toInstant,
              ).create,
            )

          // Create ValidatorLivenessActivityRecord
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

          // Create ValidatorRewardCoupon
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = sv1Backend.config.ledgerApiUser,
              actAs = Seq(dsoParty),
              readAs = Seq.empty,
              update = new ValidatorRewardCoupon(
                dsoParty.toProtoPrimitive,
                beneficiaryParty.party.toProtoPrimitive,
                validatorRewardAmount.bigDecimal,
                issuingRound.round,
              ).create,
            )

          // Create DevelopmentFundCoupon
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = sv1Backend.config.ledgerApiUser,
              actAs = Seq(dsoParty),
              readAs = Seq.empty,
              update = new DevelopmentFundCoupon(
                dsoParty.toProtoPrimitive,
                beneficiaryParty.party.toProtoPrimitive,
                dsoParty.toProtoPrimitive, // fundManager = dso
                developmentFundAmount.bigDecimal,
                env.environment.clock.now.plus(Duration.ofDays(1)).toInstant,
                "test development fund coupon",
              ).create,
            )
        }

        // Advance time to collect all rewards
        advanceRoundsToNextRoundOpening
        advanceTimeForRewardAutomationToRunForCurrentRound

        val (_, issuingRoundsAfter) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val issuingRoundsMap = issuingRoundsAfter.view.map(r => r.payload.round -> r.payload).toMap

        clue("All reward contracts should be consumed") {
          eventually() {
            externalPartyWallet.store.listUnclaimedActivityRecords().futureValue shouldBe empty
            externalPartyWallet.store
              .listSortedAppRewards(issuingRoundsMap)
              .futureValue shouldBe empty
            externalPartyWallet.store
              .listSortedValidatorRewards(Some(issuingRoundsMap.keySet.map(_.number)))
              .futureValue shouldBe empty
            externalPartyWallet.store
              .listSortedLivenessActivityRecords(issuingRoundsMap)
              .futureValue shouldBe empty
            externalPartyWallet.store
              .listDevelopmentFundCoupons()
              .futureValue shouldBe empty
          }
        }
      }

      // Verify balance increase
      val balanceAfter = getBalance()
      val actualIncrease = balanceAfter - balanceBefore

      val expectedTotalReward =
        (appRewardAmount * BigDecimal(issuingRound.issuancePerUnfeaturedAppRewardCoupon)) +
          (BigDecimal(
            issuingRound.optIssuancePerValidatorFaucetCoupon.orElse(java.math.BigDecimal.ZERO)
          )) +
          (validatorRewardAmount * BigDecimal(issuingRound.issuancePerValidatorRewardCoupon)) +
          unclaimedActivityAmount +
          developmentFundAmount

      actualIncrease shouldBe expectedTotalReward

      // Test merge behavior at 2x limit
      def getAmuletCount() = {
        externalPartyWallet.store.multiDomainAcsStore
          .listContracts(Amulet.COMPANION, Limit.DefaultLimit)
          .futureValue
          .size
      }

      clue("Test that amulets get merge at 2x limit") {
        val currentCount = getAmuletCount()
        val mergeLimit = DefaultAmuletMergeLimit

        // Transfer enough amulets to reach exactly 2x the merge limit
        val amuletsNeededFor2x = (2 * mergeLimit) - currentCount
        (1 to amuletsNeededFor2x).foreach { i =>
          aliceValidatorWalletClient.transferPreapprovalSend(
            beneficiaryParty.party,
            10.0,
            s"transfer-$i",
          )
        }

        clue(s"Verify amulets merged to mergeLimit") {
          eventually() {
            val count = getAmuletCount()
            count shouldBe mergeLimit
          }
        }
      }
    }
  }

  private def collectRewardsAndMergeAmuletsTrigger(
      validatorBackend: ValidatorAppBackendReference,
      userName: String,
  ): Trigger =
    validatorBackend
      .userWalletAutomation(userName)
      .futureValue
      .trigger[CollectRewardsAndMergeAmuletsTrigger]

  private def mintingDelegationCollectRewardsTrigger(
      validatorBackend: ValidatorAppBackendReference,
      externalParty: PartyId,
  ): Trigger =
    validatorBackend.appState.walletManager
      .valueOrFail("WalletManager is expected to be defined")
      .externalPartyWalletManager
      .lookupExternalPartyWallet(externalParty)
      .valueOrFail(s"Expected ${externalParty} to have an external party wallet")
      .automation
      .trigger[MintingDelegationCollectRewardsTrigger]

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
