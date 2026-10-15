// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  Amulet,
  AppRewardCoupon,
  DevelopmentFundCoupon,
  RewardCouponV2,
  SvRewardCoupon,
  UnclaimedActivityRecord,
  ValidatorRewardCoupon,
  ValidatorRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLivenessActivityRecord
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation as mintingDelegationCodegen
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
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.updateAllValidatorConfigs
import org.lfdecentralizedtrust.splice.store.{HardLimit, Limit}
import org.lfdecentralizedtrust.splice.util.{TimeTestUtil, TriggerTestUtil, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.config.{
  AppRewardBeneficiaryConfig,
  RewardSharingConfig,
}
import com.digitalasset.canton.topology.PartyId

import java.time.Duration
import scala.jdk.CollectionConverters.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceWallet_0_1_16
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_19
class WalletMintingDelegationTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  private val DefaultAmuletMergeLimit = 10
  // Pre-generate key pairs so external party IDs are known at config time
  private val sharingAppProvider = preGenerateExternalParty("sharing_app_provider")
  private val sharingRecipient = preGenerateExternalParty("sharing_recipient")
  private val externalSharingProvider = preGenerateExternalParty("external_sharing_provider")
  private val ttlSharingProvider = preGenerateExternalParty("ttl_sharing_provider")
  private val ttlSharingRecipient = preGenerateExternalParty("ttl_sharing_recipient")

  // We create many coupons directly, so avoid running sanity checks
  override protected def runUpdateHistorySanityCheck: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      .withTrafficTopupsDisabled
      .addConfigTransforms((_, config) =>
        // Configure sharing for the "sharing_app_provider" external party:
        // 40% to "sharing_recipient", remainder stays with provider.
        updateAllValidatorConfigs { case (name, c) =>
          if (name == "aliceValidator") {
            c.copy(
              rewardSharingConfigByParty = Map[String, RewardSharingConfig](
                sharingAppProvider.partyId.toProtoPrimitive -> RewardSharingConfig.BuiltIn(
                  minTtlAfterSharing = NonNegativeFiniteDuration.ofHours(25),
                  beneficiaries = Seq(
                    AppRewardBeneficiaryConfig(sharingRecipient.partyId, BigDecimal(0.4))
                  ),
                ),
                externalSharingProvider.partyId.toProtoPrimitive -> RewardSharingConfig.External(),
                ttlSharingProvider.partyId.toProtoPrimitive -> RewardSharingConfig.BuiltIn(
                  minTtlAfterSharing = NonNegativeFiniteDuration.ofMinutes(6),
                  beneficiaries =
                    Seq(AppRewardBeneficiaryConfig(ttlSharingRecipient.partyId, BigDecimal(0.4))),
                ),
              )
            )
          } else c
        }(config)
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
          aliceValidatorWalletClient
            .listMintingDelegationProposals()
            .proposals shouldBe empty withClue "MintingDelegationProposals"
          aliceValidatorWalletClient
            .listMintingDelegations()
            .delegations shouldBe empty withClue "MintingDelegations"

          val (_, proposal0Cid) = actAndCheck(
            "Create minting delegation proposal for beneficiary2",
            createMintingDelegationProposal(beneficiary2Party, validatorParty, expiresAt),
          )(
            "Proposal is visible to validator",
            _ => {
              val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
              proposals.proposals should have size 1 withClue "proposals"
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
              delegations.delegations should have size 1 withClue "delegations"
            },
          )

          actAndCheck(
            "Create another proposal for beneficiary2",
            createMintingDelegationProposal(beneficiary2Party, validatorParty, expiresAt),
          )(
            "Proposal is visible to validator",
            _ => {
              val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
              proposals.proposals should have size 1 withClue "proposals"
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
              proposals.proposals should have size 2 withClue "proposals"
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
              delegations.delegations should have size 2 withClue "delegations"
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
              proposals.proposals should have size 2 withClue "proposals"
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
                .proposals should have size 1 withClue "proposals",
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
              proposals.proposals should have size 2 withClue "proposals"
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
                .proposals should have size 1 withClue "proposals"
              val delegations = aliceValidatorWalletClient.listMintingDelegations()
              delegations.delegations should have size 2 withClue "delegations"
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
              proposals.proposals should have size 2 withClue "proposals"
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
                .proposals should have size 1 withClue "proposals"
              val delegations = aliceValidatorWalletClient.listMintingDelegations()
              delegations.delegations should have size 2 withClue "delegations"
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
              proposals.proposals should have size 2 withClue "proposals"
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
              delegations.delegations should have size 3 withClue "delegations"
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
              proposals.proposals should have size 2 withClue "proposals"
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
              proposals should have size 1 withClue "proposals"
            }
          }
        }
    }
  }

  "MintingDelegationCollectRewardsTrigger" should {
    "collect rewards for all coupons owned by the beneficiary" in { implicit env =>
      // This test verifies that MintingDelegationCollectRewardsTrigger collects
      // ValidatorRewardCoupons, AppRewardCoupons, ValidatorLivenessActivityRecords,
      // UnclaimedActivityRecords, and SvRewardCoupons.

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
          proposals.proposals should have size 1 withClue "proposals"
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
          delegations.delegations should have size 1 withClue "delegations"
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
      val delayedDevelopmentFundAmount = BigDecimal(400.0)
      val rewardCouponV2Amount = BigDecimal(1000.0)
      val svRewardWeight = 5L
      val mintDelay = Duration.ofMinutes(45)
      val mintAfter = env.environment.clock.now.plus(mintDelay).toInstant

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

          // Create SvRewardCoupon (sv field is not validated on redemption)
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = sv1Backend.config.ledgerApiUser,
              actAs = Seq(dsoParty),
              readAs = Seq.empty,
              update = new SvRewardCoupon(
                dsoParty.toProtoPrimitive,
                dsoParty.toProtoPrimitive, // sv
                beneficiaryParty.party.toProtoPrimitive,
                issuingRound.round,
                svRewardWeight,
              ).create,
            )

          // Create a DevelopmentFundCoupon that is mintable immediately
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
                java.util.Optional.empty(), // mintAfter
              ).create,
            )

          // Create a second DevelopmentFundCoupon with a mintAfter delay
          sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = sv1Backend.config.ledgerApiUser,
              actAs = Seq(dsoParty),
              readAs = Seq.empty,
              update = new DevelopmentFundCoupon(
                dsoParty.toProtoPrimitive,
                beneficiaryParty.party.toProtoPrimitive,
                dsoParty.toProtoPrimitive, // fundManager = dso
                delayedDevelopmentFundAmount.bigDecimal,
                env.environment.clock.now.plus(Duration.ofDays(30)).toInstant,
                "delayed test development fund coupon",
                java.util.Optional.of(mintAfter),
              ).create,
            )

          // Create RewardCouponV2 (assigned to beneficiary)
          createRewardCouponsV2(
            Seq((beneficiaryParty.party, rewardCouponV2Amount, Some(beneficiaryParty.party))),
            round = Some(issuingRound.round),
          )
        }

        // Advance time to collect all rewards
        advanceRoundsToNextRoundOpening
        advanceTimeForRewardAutomationToRunForCurrentRound

        val (_, issuingRoundsAfter) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
        val issuingRoundsMap = issuingRoundsAfter.view.map(r => r.payload.round -> r.payload).toMap

        clue("All reward contracts except the delayed development fund coupon should be consumed") {
          eventually() {
            externalPartyWallet.store
              .listUnclaimedActivityRecords()
              .futureValue shouldBe empty withClue "UnclaimedActivityRecord"
            externalPartyWallet.store
              .listSortedAppRewards(issuingRoundsMap)
              .futureValue shouldBe empty withClue "AppReward"
            externalPartyWallet.store
              .listSortedValidatorRewards(Some(issuingRoundsMap.keySet.map(_.number)))
              .futureValue shouldBe empty withClue "ValidatorReward"
            externalPartyWallet.store
              .listSortedLivenessActivityRecords(issuingRoundsMap)
              .futureValue shouldBe empty withClue "LivenessActivityRecord"
            inside(externalPartyWallet.store.listDevelopmentFundCoupons().futureValue) {
              case Seq(remaining) =>
                remaining.payload.reason shouldBe "delayed test development fund coupon"
            }
            externalPartyWallet.store
              .listRewardCouponsV2(includeUnassigned = true, includeAssigned = true)
              .futureValue shouldBe empty withClue "RewardCouponV2"
            externalPartyWallet.store
              .listSortedSvRewardCoupons(issuingRoundsMap)
              .futureValue shouldBe empty withClue "SvRewardCoupon"
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
          (BigDecimal(svRewardWeight) * BigDecimal(issuingRound.issuancePerSvRewardCoupon)) +
          unclaimedActivityAmount +
          developmentFundAmount +
          rewardCouponV2Amount

      actualIncrease shouldBe expectedTotalReward

      actAndCheck(
        "Advance past the delayed coupon's mintAfter, one round at a time",
        advanceRoundsUntil(mintAfter),
      )(
        "The delayed development fund coupon is collected",
        _ => {
          advanceTime(Duration.ofSeconds(1))
          externalPartyWallet.store
            .listDevelopmentFundCoupons()
            .futureValue shouldBe empty withClue "DevelopmentFundCoupon after mintAfter"
          getBalance() shouldBe balanceAfter + delayedDevelopmentFundAmount
        },
      )

      // Test merge behavior at 2x limit
      def getAmuletCount() = {
        externalPartyWallet.store.multiDomainAcsStore
          .listContracts(Amulet.COMPANION, HardLimit.tryCreate(Limit.DefaultMaxPageSize))
          .futureValue
          .size
      }

      clue("Test that amulets get merge at 2x limit") {
        val currentCount = getAmuletCount()
        val mergeLimit = DefaultAmuletMergeLimit

        // Transfer enough amulets to reach exactly 2x the merge limit.
        // Pause the validator's own collect-and-merge trigger during the
        // transfers so it cannot archive a sender input amulet mid-send,
        // which would reject the transfer with INACTIVE_CONTRACTS.
        val amuletsNeededFor2x = (2 * mergeLimit) - currentCount
        setTriggersWithin(triggersToPauseAtStart = Seq(validatorRewardTrigger)) {
          (1 to amuletsNeededFor2x).foreach { i =>
            aliceValidatorWalletClient.transferPreapprovalSend(
              beneficiaryParty.party,
              10.0,
              s"transfer-$i",
            )
          }
        }

        clue(s"Verify amulets merged to mergeLimit") {
          eventually() {
            val count = getAmuletCount()
            count shouldBe mergeLimit
          }
        }
      }
    }

    "assign and mint unassigned V2 coupons when sharing is configured" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100.0)
      aliceValidatorWalletClient.tap(100.0)

      val beneficiaryParty = onboardExternalParty(aliceValidatorBackend, sharingAppProvider)
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiaryParty)

      val recipientParty = onboardExternalParty(aliceValidatorBackend, sharingRecipient)
      createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, recipientParty)

      val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
      val (_, proposalContractId) = actAndCheck(
        "Create minting delegation proposal",
        createMintingDelegationProposal(beneficiaryParty, aliceParty, expiresAt),
      )(
        "Proposal is visible",
        _ => {
          val proposals = aliceWalletClient.listMintingDelegationProposals()
          proposals.proposals should have size 1 withClue "proposals"
          proposals.proposals.head.contract.contractId
        },
      )

      actAndCheck(
        "Alice accepts the proposal",
        aliceWalletClient.acceptMintingDelegationProposal(proposalContractId),
      )(
        "Delegation is created",
        _ => {
          val delegations = aliceWalletClient.listMintingDelegations()
          delegations.delegations should have size 1 withClue "delegations"
        },
      )

      val unassignedAmount1 = BigDecimal(1000.0)
      val unassignedAmount2 = BigDecimal(500.0)
      val assignedAmount = BigDecimal(250.0)

      val externalPartyMintingDelegationTrigger = mintingDelegationCollectRewardsTrigger(
        aliceValidatorBackend,
        beneficiaryParty.party,
      )

      val externalPartyWallet = aliceValidatorBackend.appState.walletManager
        .valueOrFail("WalletManager is expected to be defined")
        .externalPartyWalletManager
        .lookupExternalPartyWallet(beneficiaryParty.party)
        .valueOrFail(
          s"Expected ${beneficiaryParty.party} to have an external party wallet"
        )

      // Pause the trigger, create two unassigned and one already-assigned
      // V2 coupon, then resume — the trigger should batch-assign
      // beneficiaries to both unassigned coupons and mint all in one transaction.
      setTriggersWithin(triggersToPauseAtStart = Seq(externalPartyMintingDelegationTrigger)) {
        actAndCheck(
          "Create V2 coupons",
          createRewardCouponsV2(
            Seq(
              (beneficiaryParty.party, unassignedAmount1, None),
              (beneficiaryParty.party, unassignedAmount2, None),
              (beneficiaryParty.party, assignedAmount, Some(beneficiaryParty.party)),
            )
          ),
        )(
          "Coupons are visible in store",
          _ =>
            externalPartyWallet.store.multiDomainAcsStore
              .listContracts(RewardCouponV2.COMPANION)
              .futureValue should have size 3,
        )
      }

      clue("Unassigned V2 coupons should be consumed by assign-and-mint") {
        eventually() {
          val v2Coupons = externalPartyWallet.store.multiDomainAcsStore
            .listContracts(RewardCouponV2.COMPANION)
            .futureValue
          v2Coupons.filter(_.payload.beneficiary.isEmpty) shouldBe
            empty withClue "No unassigned coupons should remain"
        }
      }

      // Beneficiary balance = provider's 60% of each unassigned coupon + full assigned coupon
      clue("Beneficiary's balance reflects shared and directly minted coupons") {
        eventually() {
          val beneficiaryBalance = BigDecimal(
            aliceValidatorBackend
              .getExternalPartyBalance(beneficiaryParty.party)
              .totalUnlockedCoin
          )
          val providerShare = BigDecimal(0.6)
          val expectedBalance =
            (unassignedAmount1 + unassignedAmount2) * providerShare + assignedAmount
          beneficiaryBalance shouldBe expectedBalance withClue
            "Balance should include provider's 60% of each unassigned coupon + directly minted assigned coupon"
        }
      }
    }

    // Hold-back then assign+mint: coupons start above the sharing threshold and are shared only
    // once near expiry. ttl 8m > minTtl 6m -> held (Phase 1); advanceTime(4m) leaves remaining
    // ttl 4m <= 6m -> gate opens (Phase 2). Keep the advance under one mining-round tick (10m)
    // or the coupons' round ages out and the mint can't collect them. Only 2 coupons/amulets
    // exist here, far below the merge threshold of 2 x amuletMergeLimit (DefaultAmuletMergeLimit
    // = 10 -> 20; see MintingDelegationCollectRewardsTrigger.selectAmuletsToMerge), so no forced
    // merge can trigger sharing before the TTL gate opens. Recipient gets 40%; provider keeps
    // 60% (added as a beneficiary of its own coupon).
    "hold back unassigned V2 coupons until the TTL threshold, then assign and mint" in {
      implicit env =>
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)
        aliceValidatorWalletClient.tap(100.0)

        val delegationValidity = Duration.ofDays(30)
        val couponTtl = Duration.ofMinutes(8) // > minTtl (6m) -> held in Phase 1
        val advanceIntoGate =
          Duration.ofMinutes(4) // leaves 4m remaining <= 6m -> gate opens in Phase 2

        val providerParty = onboardExternalParty(aliceValidatorBackend, ttlSharingProvider)
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, providerParty)

        val recipientParty = onboardExternalParty(aliceValidatorBackend, ttlSharingRecipient)
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, recipientParty)

        val expiresAt = env.environment.clock.now.plus(delegationValidity).toInstant
        val (_, proposalContractId) = actAndCheck(
          "Create minting delegation proposal",
          createMintingDelegationProposal(providerParty, aliceParty, expiresAt),
        )(
          "Proposal is visible",
          _ => {
            val proposals = aliceWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 1 withClue "proposals"
            proposals.proposals.head.contract.contractId
          },
        )
        actAndCheck(
          "Alice accepts the proposal",
          aliceWalletClient.acceptMintingDelegationProposal(proposalContractId),
        )(
          "Delegation is created",
          _ =>
            aliceWalletClient
              .listMintingDelegations()
              .delegations should have size 1 withClue "delegations",
        )

        val wallet = eventually() {
          aliceValidatorBackend.appState.walletManager
            .valueOrFail("Wallet manager is expected to be defined")
            .externalPartyWalletManager
            .lookupExternalPartyWallet(providerParty.party)
            .valueOrFail(s"Expected ${providerParty.party} to have an external party wallet")
        }

        val trigger = wallet.automation
          .trigger[MintingDelegationCollectRewardsTrigger]

        def unassignedCount(): Int =
          wallet.store.multiDomainAcsStore
            .listContracts(RewardCouponV2.COMPANION)
            .futureValue
            .count(_.payload.beneficiary.isEmpty)

        // Provider is an observer of every coupon (providerIsObserver = true), so its store also
        // sees the coupons assigned to the recipient. After assign+mint the provider's own 60%
        // coupons are consumed by the transfer, leaving the recipient's 40% coupons unminted
        // (the recipient has no delegation to collect them).
        def assignedToRecipientTotal(): BigDecimal =
          wallet.store.multiDomainAcsStore
            .listContracts(RewardCouponV2.COMPANION)
            .futureValue
            .filter(c =>
              c.payload.beneficiary.isPresent &&
                c.payload.beneficiary.get == recipientParty.party.toProtoPrimitive
            )
            .map(c => BigDecimal(c.payload.amount))
            .sum

        val amount1 = BigDecimal(1000.0)
        val amount2 = BigDecimal(500.0)

        setTriggersWithin(triggersToPauseAtStart = Seq(trigger)) {
          actAndCheck(
            "Create only unassigned V2 coupons, ttl (8m) > minTtl (6m)",
            createRewardCouponsV2(
              Seq((providerParty.party, amount1, None), (providerParty.party, amount2, None)),
              ttl = couponTtl,
            ),
          )("Both coupons visible and unassigned", _ => unassignedCount() shouldBe 2)

          clue("Gate is false while above threshold: one explicit poll does no work") {
            trigger.runOnce().futureValue shouldBe false
          }

          clue("Above threshold: coupons stay unassigned and nothing is minted yet") {
            unassignedCount() shouldBe 2
            BigDecimal(
              aliceValidatorBackend.getExternalPartyBalance(providerParty.party).totalUnlockedCoin
            ) shouldBe BigDecimal(0)
          }

          advanceTime(advanceIntoGate)

          clue("Gate is true once within minTtl: poll assigns + mints") {
            eventually() { trigger.runOnce().futureValue shouldBe true }
          }
        }

        clue("Unassigned coupons consumed by assign-and-mint") {
          eventually() { unassignedCount() shouldBe 0 }
        }

        // Provider mints only its own 60%; the recipient's 40% is carved out into separate
        // coupons that the recipient would mint via its own delegation (absent here).
        clue("Provider mints exactly its 60% share") {
          eventually() {
            BigDecimal(
              aliceValidatorBackend.getExternalPartyBalance(providerParty.party).totalUnlockedCoin
            ) shouldBe (amount1 + amount2) * BigDecimal(0.6)
          }
        }

        clue("Recipient's 40% is assigned as unminted coupons") {
          eventually() {
            assignedToRecipientTotal() shouldBe (amount1 + amount2) * BigDecimal(0.4)
          }
        }
    }

    "mint already-assigned V2 coupons but hold back unassigned ones in external sharing mode" in {
      implicit env =>
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        aliceWalletClient.tap(100.0)
        aliceValidatorWalletClient.tap(100.0)

        val externalSharingParty =
          onboardExternalParty(aliceValidatorBackend, externalSharingProvider)
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, externalSharingParty)

        val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
        val (_, proposalContractId) = actAndCheck(
          "Create minting delegation proposal",
          createMintingDelegationProposal(externalSharingParty, aliceParty, expiresAt),
        )(
          "Proposal is visible",
          _ => {
            val proposals = aliceWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 1 withClue "proposals"
            proposals.proposals.head.contract.contractId
          },
        )

        actAndCheck(
          "Alice accepts the proposal",
          aliceWalletClient.acceptMintingDelegationProposal(proposalContractId),
        )(
          "Delegation is created",
          _ => {
            val delegations = aliceWalletClient.listMintingDelegations()
            delegations.delegations should have size 1 withClue "delegations"
          },
        )

        val unassignedAmount1 = BigDecimal(1000.0)
        val unassignedAmount2 = BigDecimal(500.0)
        val assignedAmount = BigDecimal(250.0)

        val externalPartyMintingDelegationTrigger = mintingDelegationCollectRewardsTrigger(
          aliceValidatorBackend,
          externalSharingParty.party,
        )

        val externalPartyWallet = aliceValidatorBackend.appState.walletManager
          .valueOrFail("WalletManager is expected to be defined")
          .externalPartyWalletManager
          .lookupExternalPartyWallet(externalSharingParty.party)
          .valueOrFail(
            s"Expected ${externalSharingParty.party} to have an external party wallet"
          )

        // Pause the trigger, create two unassigned and one already-assigned V2
        // coupon, then resume. In external sharing mode the off-node automation
        // owns beneficiary assignment, so the trigger must leave the unassigned
        // coupons untouched while still minting the already-assigned coupon.
        setTriggersWithin(triggersToPauseAtStart = Seq(externalPartyMintingDelegationTrigger)) {
          actAndCheck(
            "Create V2 coupons",
            createRewardCouponsV2(
              Seq(
                (externalSharingParty.party, unassignedAmount1, None),
                (externalSharingParty.party, unassignedAmount2, None),
                (externalSharingParty.party, assignedAmount, Some(externalSharingParty.party)),
              )
            ),
          )(
            "Coupons are visible in store",
            _ =>
              externalPartyWallet.store.multiDomainAcsStore
                .listContracts(RewardCouponV2.COMPANION)
                .futureValue should have size 3,
          )
        }

        clue("Assigned coupon is minted while unassigned coupons are held back untouched") {
          eventually() {
            val v2Coupons = externalPartyWallet.store.multiDomainAcsStore
              .listContracts(RewardCouponV2.COMPANION)
              .futureValue
            v2Coupons.filter(_.payload.beneficiary.isEmpty) should have size 2 withClue
              "external sharing mode must leave unassigned coupons untouched"
            v2Coupons.filter(_.payload.beneficiary.isPresent) shouldBe
              empty withClue "the already-assigned coupon must be minted and consumed"
          }
        }

        // Only the already-assigned coupon is minted; the two unassigned coupons
        // are neither shared nor collected, so they do not contribute to the balance.
        clue("Balance reflects only the directly minted assigned coupon") {
          eventually() {
            val balance = BigDecimal(
              aliceValidatorBackend
                .getExternalPartyBalance(externalSharingParty.party)
                .totalUnlockedCoin
            )
            balance shouldBe assignedAmount withClue
              "external sharing mode mints only the already-assigned coupon, not the held-back unassigned ones"
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
