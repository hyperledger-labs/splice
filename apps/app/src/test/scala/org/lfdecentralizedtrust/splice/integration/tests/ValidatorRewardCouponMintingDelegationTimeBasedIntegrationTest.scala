package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  ValidatorRewardCoupon,
  ValidatorRight,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.{
  PaymentTransferContext,
  TransferContext,
  TransferInput,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.transferinput.InputValidatorRewardCoupon
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation as mintingDelegationCodegen
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.console.ValidatorAppBackendReference
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.util.{
  SpliceUtil,
  TimeTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.{
  CollectRewardsAndMergeAmuletsTrigger,
  MintingDelegationCollectRewardsTrigger,
}

import java.time.Duration
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.*

class ValidatorRewardCouponMintingDelegationTimeBasedIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with TimeTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // TODO (#965) remove and fix test failures
      .withAmuletPrice(walletAmuletPrice)
      // Enable non-zero fees so ValidatorRewardCoupons are created for external party transfers
      .addConfigTransform((_, config) => ConfigTransforms.disableZeroFees()(config))

  // TODO (#965) remove and fix test failures
  override def walletAmuletPrice = SpliceUtil.damlDecimal(1.0)

  "A wallet with minting delegation" should {

    "list and automatically collect app & validator rewards for external party beneficiary" in {
      implicit env =>
        val (_, _) = onboardAliceAndBob()
        waitForWalletUser(aliceValidatorWalletClient)
        waitForWalletUser(bobValidatorWalletClient)

        // Fund the validator wallet for external party setup and transfers
        aliceValidatorWalletClient.tap(100.0)

        // Set up external party beneficiary1 and beneficiary2
        // 1: delegates minting to alice's validator
        // 2: receives a transfer from 1
        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
        val beneficiary1 = onboardExternalParty(aliceValidatorBackend, Some("beneficiary1"))
        val beneficiary2 = onboardExternalParty(aliceValidatorBackend, Some("beneficiary2"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary1)
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary2)

        // Create ValidatorRight for beneficiary1 so that ValidatorRewardCoupons can be collected
        // ValidatorRight has user=externalParty, validator=externalParty (self-validator)
        actAndCheck(
          "Create ValidatorRight for beneficiary1",
          createValidatorRight(beneficiary1),
        )(
          "ValidatorRight is created and external party wallet is started",
          _ => {
            // Verify ValidatorRight exists and wait for external party wallet automation to start
            val validatorRights =
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .filterJava(ValidatorRight.COMPANION)(
                  beneficiary1.party,
                  _.data.validator == beneficiary1.party.toProtoPrimitive,
                )
            validatorRights should have size 1
          },
        )

        // Create minting delegation from beneficiary1 to alice's validator
        val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
        val (_, proposalCid) = actAndCheck(
          "Create minting delegation proposal for beneficiary1",
          createMintingDelegationProposal(beneficiary1, aliceValidatorParty, expiresAt),
        )(
          "Proposal is visible to validator",
          _ => {
            val proposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            proposals.proposals should have size 1
            proposals.proposals.head.contract.contractId
          },
        )

        actAndCheck(
          "Accept minting delegation proposal",
          aliceValidatorWalletClient.acceptMintingDelegationProposal(proposalCid),
        )(
          "Delegation is created",
          _ => {
            val delegations = aliceValidatorWalletClient.listMintingDelegations()
            delegations.delegations should have size 1
          },
        )

        // Wait for the external party wallet to be created - this is required for the
        // MintingDelegationCollectRewardsTrigger to run
        val externalPartyWallet = eventually() {
          aliceValidatorBackend.appState.walletManager
            .valueOrFail("WalletManager is expected to be defined")
            .externalPartyWalletManager
            .lookupExternalPartyWallet(beneficiary1.party)
            .valueOrFail(
              s"Expected ${beneficiary1.party} to have an external party wallet"
            )
        }
        logger.debug(
          s"External party wallet for beneficiary1 is ready: ${externalPartyWallet.store.key}"
        )

        // Helper function to get beneficiary1's balance
        def getBalance(): BigDecimal = BigDecimal(
          aliceValidatorBackend
            .getExternalPartyBalance(beneficiary1.party)
            .totalUnlockedCoin
        )

        // Advance rounds to ensure we have proper issuing rounds before creating coupons
        // Following the pattern from WalletMintingDelegationTimeBasedIntegrationTest
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening

        // Get an issuing round to know what rounds are available
        val issuingRound = eventually() {
          val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          issuingRounds.toList.headOption.value.payload
        }
        logger.debug(s"Current issuing round: ${issuingRound.round.number}")

        // Pause the validator's own reward collection trigger which would
        // normally mint coupons for itself, to avoid interference
        val validatorRewardTrigger = collectRewardsAndMergeAmuletsTrigger(
          aliceValidatorBackend,
          aliceValidatorWalletClient.config.ledgerApiUser,
        )

        setTriggersWithin(triggersToPauseAtStart = Seq(validatorRewardTrigger)) {
          val externalPartyMintingDelegationTrigger = mintingDelegationCollectRewardsTrigger(
            aliceValidatorBackend,
            beneficiary1.party,
          )

          // Pause minting delegation trigger while creating coupons to ensure we control timing
          setTriggersWithin(triggersToPauseAtStart = Seq(externalPartyMintingDelegationTrigger)) {
            // Transfer from alice's validator wallet to beneficiary1 using transfer preapproval
            // This generates activity for alice's validator (the provider for beneficiary1)
            // Send 30 CC so beneficiary1 has enough to transfer 25 CC to beneficiary2
            aliceValidatorWalletClient.transferPreapprovalSend(
              beneficiary1.party,
              30.0,
              "validator-to-beneficiary1",
            )

            // Wait for beneficiary1 to receive the amulets
            eventually() {
              getBalance().toDouble should be > 0.0
            }

            // Transfer from beneficiary1 to beneficiary2
            // This generates ValidatorRewardCoupons for beneficiary1 (the sender)
            // The transfer amount must be large enough (>15 amulets) so that the resulting
            // ValidatorRewardCoupon, when minted (coupon × 0.2 issuance rate), exceeds the
            // create fee (0.03). Otherwise the reward is entirely consumed by fees.
            val prepareSend = aliceValidatorBackend.prepareTransferPreapprovalSend(
              beneficiary1.party,
              beneficiary2.party,
              BigDecimal(25.0),
              CantonTimestamp.now().plus(Duration.ofHours(1)),
              0L,
              Some("beneficiary1-to-beneficiary2"),
            )
            aliceValidatorBackend.submitTransferPreapprovalSend(
              beneficiary1.party,
              prepareSend.transaction,
              HexString.toHexString(
                crypto(env.executionContext)
                  .signBytes(
                    HexString.parseToByteString(prepareSend.txHash).value,
                    beneficiary1.privateKey.asInstanceOf[SigningPrivateKey],
                    usage = SigningKeyUsage.ProtocolOnly,
                  )
                  .value
                  .toProtoV30
                  .signature
              ),
              publicKeyAsHexString(beneficiary1.publicKey),
            )

            // Wait for beneficiary2 to receive the amulets
            eventually() {
              aliceValidatorBackend
                .getExternalPartyBalance(beneficiary2.party)
                .totalUnlockedCoin
                .toDouble should be > 0.0
            }

            // Check that beneficiary1 has ValidatorRewardCoupons from the transfer they made
            // This must be checked BEFORE advancing rounds, as the MintingDelegationCollectRewardsTrigger
            // will collect these coupons during round processing
            // Check that beneficiary1 has ValidatorRewardCoupons from the transfer they made
            // and capture the coupon's round number for later verification
            val couponRound =
              clue("Verify beneficiary1 has ValidatorRewardCoupons from the transfer") {
                eventually(40.seconds) {
                  // First check all coupons visible to beneficiary1
                  val allCouponsVisibleToBeneficiary1 =
                    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                      .filterJava(ValidatorRewardCoupon.COMPANION)(
                        beneficiary1.party,
                        _ => true,
                      )
                  logger.debug(
                    s"All ValidatorRewardCoupons visible to beneficiary1: ${allCouponsVisibleToBeneficiary1.size}"
                  )
                  // Log details of each coupon to understand the user field
                  allCouponsVisibleToBeneficiary1.foreach { coupon =>
                    logger.debug(
                      s"ValidatorRewardCoupon visible to beneficiary1: user=${coupon.data.user}, round=${coupon.data.round.number}, amount=${coupon.data.amount}"
                    )
                  }

                  // Now check coupons where user=beneficiary1 (which is what the store filters by)
                  val beneficiary1Coupons =
                    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                      .filterJava(ValidatorRewardCoupon.COMPANION)(
                        beneficiary1.party,
                        _.data.user == beneficiary1.party.toProtoPrimitive,
                      )
                  logger.debug(
                    s"ValidatorRewardCoupons with user=beneficiary1: ${beneficiary1Coupons.size}"
                  )
                  beneficiary1Coupons should not be empty
                  // Return the round number of the first coupon
                  beneficiary1Coupons.head.data.round.number
                }
              }

            // CRITICAL: Advance rounds BEFORE unpausing the minting delegation trigger
            // The trigger only collects coupons from ISSUING rounds, not OPEN rounds.
            // Transfers create coupons in the current OPEN round, so we must advance
            // rounds to transition the coupon's round from open → issuing BEFORE
            // the trigger runs.
            logger.debug(
              s"Coupon was created in round $couponRound, advancing rounds so it becomes issuing"
            )

            eventually() {
              val openRounds = sv1ScanBackend
                .getOpenAndIssuingMiningRounds()
                ._1
                .filter(_.payload.opensAt <= env.environment.clock.now.toInstant)
              openRounds should not be empty
            }

            // Advance rounds multiple times to ensure the transfer's round becomes issuing
            advanceRoundsToNextRoundOpening
            advanceRoundsToNextRoundOpening
            advanceRoundsToNextRoundOpening

            // Verify that the coupon's round is now in issuing rounds
            eventually() {
              val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
              val issuingRoundNumbers = issuingRounds.map(_.payload.round.number).toSet
              logger.debug(
                s"Issuing rounds after advancement: $issuingRoundNumbers, coupon round: $couponRound"
              )
              issuingRoundNumbers should contain(couponRound)
            }
          }
          // Minting delegation trigger is now unpaused and will collect coupons
          // The coupon's round is now an issuing round, so the trigger should find it

          val prevBeneficiary1Balance = getBalance()

          // Advance time to trigger the reward automation
          advanceTimeForRewardAutomationToRunForCurrentRound

          // Get current issuing rounds to verify coupons are consumed
          val (_, issuingRoundsAfter) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          val issuingRoundsMap =
            issuingRoundsAfter.view.map(r => r.payload.round -> r.payload).toMap
          logger.debug(
            s"Issuing rounds after automation: ${issuingRoundsMap.keySet.map(_.number).mkString(", ")}"
          )

          // After round advancement, the MintingDelegationCollectRewardsTrigger should have
          // collected beneficiary1's ValidatorRewardCoupons
          clue("Verify beneficiary1's ValidatorRewardCoupons are consumed from issuing rounds") {
            eventually(40.seconds) {
              // The coupons should be consumed from the store (filtered by issuing rounds)
              val remainingCoupons = externalPartyWallet.store
                .listSortedValidatorRewards(Some(issuingRoundsMap.keySet.map(_.number)))
                .futureValue
              logger.debug(
                s"Remaining ValidatorRewardCoupons in issuing rounds: ${remainingCoupons.size}"
              )
              remainingCoupons shouldBe empty
            }
          }

          clue("Verify beneficiary1's balance increased from collected rewards") {
            eventually(40.seconds) {
              // Balance should have increased from reward collection via minting delegation
              val newBeneficiary1Balance = getBalance()
              logger.debug(
                s"Balance before: $prevBeneficiary1Balance, after: $newBeneficiary1Balance"
              )
              newBeneficiary1Balance should be > prevBeneficiary1Balance
            }
          }
        }
    }

    "demonstrate vulnerability: proposal with invalid DSO can be accepted but reward collection fails" in {
      implicit env =>
        // This test demonstrates the security issue where a malicious beneficiary
        // can create a MintingDelegationProposal with an invalid DSO party.
        //
        // Discovery: The wallet store's multiDomainAcsStore filters out contracts with
        // invalid DSO due to DSO-based scoping. This means:
        // 1. Proposals with invalid DSO are NOT visible via wallet API (accidental protection)
        // 2. Delegations with invalid DSO are NOT visible to triggers (store filtering)
        // However, direct ledger API access bypasses this filtering.
        //
        // This test demonstrates that:
        // 1. A delegation with invalid DSO CAN be accepted (vulnerability)
        // 2. The delegation EXISTS on the ledger
        // 3. Minting FAILS at execution time due to DSO mismatch validation in AmuletRules_Transfer
        //
        // With Option B fix:
        // - Accept would validate DSO against AmuletRules before creating delegation

        val (_, _) = onboardAliceAndBob()
        waitForWalletUser(aliceValidatorWalletClient)

        aliceValidatorWalletClient.tap(100.0)

        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

        // Create malicious beneficiary and a recipient for transfers
        val maliciousBeneficiary =
          onboardExternalParty(aliceValidatorBackend, Some("malicious_beneficiary"))
        val recipient = onboardExternalParty(aliceValidatorBackend, Some("recipient"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, maliciousBeneficiary)
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, recipient)

        // Create ValidatorRight for the malicious beneficiary
        createValidatorRight(maliciousBeneficiary)

        val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant

        // Create proposal with INVALID DSO (use beneficiary party as fake DSO)
        val fakeDso = maliciousBeneficiary.party

        actAndCheck(
          "Create minting delegation proposal with INVALID DSO",
          createMintingDelegationProposalWithCustomDso(
            maliciousBeneficiary,
            aliceValidatorParty,
            expiresAt,
            fakeDso, // Wrong DSO!
          ),
        )(
          "Proposal exists on ledger but NOT visible via wallet store (accidental filtering)",
          _ => {
            // The wallet store accidentally filters out proposals with invalid DSO
            val walletProposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            walletProposals.proposals shouldBe empty

            // But the proposal EXISTS on the ledger - query ACS directly
            val acsProposals =
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .filterJava(mintingDelegationCodegen.MintingDelegationProposal.COMPANION)(
                  aliceValidatorParty,
                  _ => true,
                )
            acsProposals should have size 1
          },
        )

        // Get the proposal contract ID from ACS for direct exercise
        val proposal =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .filterJava(mintingDelegationCodegen.MintingDelegationProposal.COMPANION)(
              aliceValidatorParty,
              _ => true,
            )
            .head

        // Validator accepts via direct ledger API (bypassing wallet store filtering)
        // THIS SUCCEEDS - demonstrating the vulnerability (no DSO validation at accept time)
        actAndCheck(
          "Validator accepts proposal with invalid DSO via direct API (vulnerability: no validation)",
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(aliceValidatorParty),
              commands = proposal.id
                .exerciseMintingDelegationProposal_Accept(
                  java.util.Optional
                    .empty[mintingDelegationCodegen.MintingDelegation.ContractId]()
                )
                .commands
                .asScala
                .toSeq,
            ),
        )(
          "Delegation is created with invalid DSO (vulnerability)",
          _ => {
            val delegations =
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .filterJava(mintingDelegationCodegen.MintingDelegation.COMPANION)(
                  aliceValidatorParty,
                  _ => true,
                )
            delegations should have size 1
            // Verify the delegation has the WRONG DSO
            delegations.head.data.dso shouldBe fakeDso.toProtoPrimitive
          },
        )

        // Fund malicious beneficiary so they can make a transfer
        aliceValidatorWalletClient.transferPreapprovalSend(
          maliciousBeneficiary.party,
          30.0,
          "funding-malicious-beneficiary",
        )

        eventually() {
          aliceValidatorBackend
            .getExternalPartyBalance(maliciousBeneficiary.party)
            .totalUnlockedCoin
            .toDouble should be > 0.0
        }

        // Pause triggers to control timing
        val validatorRewardTrigger = collectRewardsAndMergeAmuletsTrigger(
          aliceValidatorBackend,
          aliceValidatorWalletClient.config.ledgerApiUser,
        )

        setTriggersWithin(triggersToPauseAtStart = Seq(validatorRewardTrigger)) {
          // Transfer from malicious beneficiary to recipient
          // This generates ValidatorRewardCoupons for malicious beneficiary (naturally created)
          val prepareSend = aliceValidatorBackend.prepareTransferPreapprovalSend(
            maliciousBeneficiary.party,
            recipient.party,
            BigDecimal(25.0),
            CantonTimestamp.now().plus(Duration.ofHours(1)),
            0L,
            Some("malicious-to-recipient"),
          )
          aliceValidatorBackend.submitTransferPreapprovalSend(
            maliciousBeneficiary.party,
            prepareSend.transaction,
            HexString.toHexString(
              crypto(env.executionContext)
                .signBytes(
                  HexString.parseToByteString(prepareSend.txHash).value,
                  maliciousBeneficiary.privateKey.asInstanceOf[SigningPrivateKey],
                  usage = SigningKeyUsage.ProtocolOnly,
                )
                .value
                .toProtoV30
                .signature
            ),
            publicKeyAsHexString(maliciousBeneficiary.publicKey),
          )

          // Wait for recipient to receive funds (confirms transfer completed)
          eventually() {
            aliceValidatorBackend
              .getExternalPartyBalance(recipient.party)
              .totalUnlockedCoin
              .toDouble should be > 0.0
          }

          // Verify ValidatorRewardCoupons were created for malicious beneficiary
          val couponRound = eventually(40.seconds) {
            val coupons =
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .filterJava(ValidatorRewardCoupon.COMPANION)(
                  maliciousBeneficiary.party,
                  _.data.user == maliciousBeneficiary.party.toProtoPrimitive,
                )
            coupons should not be empty
            coupons.head.data.round.number
          }

          // Advance rounds so coupon's round becomes issuing
          advanceRoundsToNextRoundOpening
          advanceRoundsToNextRoundOpening
          advanceRoundsToNextRoundOpening

          eventually() {
            val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
            issuingRounds.map(_.payload.round.number).toSet should contain(couponRound)
          }

          // Get the delegation with invalid DSO (filter by beneficiary to avoid picking up
          // the valid delegation from the first test)
          val delegation =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(mintingDelegationCodegen.MintingDelegation.COMPANION)(
                aliceValidatorParty,
                d => d.data.beneficiary == maliciousBeneficiary.party.toProtoPrimitive,
              )
              .head

          // Get the coupon to use as input
          val coupon =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(ValidatorRewardCoupon.COMPANION)(
                maliciousBeneficiary.party,
                _.data.user == maliciousBeneficiary.party.toProtoPrimitive,
              )
              .head

          // Build transfer context (similar to what the trigger does)
          val amuletRules = sv1ScanBackend.getAmuletRules()
          val (openRounds, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
          val openRound = openRounds
            .filter(_.payload.opensAt.isBefore(env.environment.clock.now.toInstant))
            .head
          val issuingRoundsForCoupon =
            issuingRounds.filter(_.payload.round.number == couponRound)

          val validatorRight =
            aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
              .filterJava(ValidatorRight.COMPANION)(
                maliciousBeneficiary.party,
                _.data.user == maliciousBeneficiary.party.toProtoPrimitive,
              )
              .head

          val transferContext = new TransferContext(
            openRound.contractId,
            issuingRoundsForCoupon.map(r => (r.payload.round, r.contractId)).toMap.asJava,
            Map(maliciousBeneficiary.party.toProtoPrimitive -> validatorRight.id).asJava,
            java.util.Optional.empty(),
          )
          val paymentContext = new PaymentTransferContext(
            amuletRules.contractId,
            transferContext,
          )
          val inputs: Seq[TransferInput] =
            Seq(new InputValidatorRewardCoupon(coupon.id))

          // Attempt to mint - this should FAIL with DSO mismatch
          // The actual error logged is "DSO party expected by caller ... does not match actual DSO party ..."
          // but CommandFailure doesn't preserve the underlying error message, so we just verify the command fails
          clue("Attempting to mint with invalid-DSO delegation should fail") {
            intercept[CommandFailure] {
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
                .submitJava(
                  actAs = Seq(aliceValidatorParty),
                  readAs = Seq(aliceValidatorParty, maliciousBeneficiary.party),
                  commands = delegation.id
                    .exerciseMintingDelegation_Mint(inputs.asJava, paymentContext)
                    .commands
                    .asScala
                    .toSeq,
                )
            }
          }

          // Verify the coupon still exists (wasn't consumed)
          clue("Coupon should still exist after failed mint attempt") {
            val remainingCoupons =
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .filterJava(ValidatorRewardCoupon.COMPANION)(
                  maliciousBeneficiary.party,
                  _.data.user == maliciousBeneficiary.party.toProtoPrimitive,
                )
            remainingCoupons should not be empty
          }
        }
    }
  }

  private val DefaultAmuletMergeLimit = 10

  private def createMintingDelegationProposal(
      beneficiaryParty: OnboardingResult,
      delegate: com.digitalasset.canton.topology.PartyId,
      expiresAt: java.time.Instant,
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    val beneficiary = beneficiaryParty.party
    val proposal = new mintingDelegationCodegen.MintingDelegationProposal(
      new mintingDelegationCodegen.MintingDelegation(
        beneficiary.toProtoPrimitive,
        delegate.toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        expiresAt,
        DefaultAmuletMergeLimit.toLong,
      )
    )
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        actingParty = beneficiaryParty.richPartyId,
        commands = proposal.create.commands.asScala.toSeq,
      )
  }

  // Helper method to create proposal with custom (potentially invalid) DSO
  // Used to demonstrate the vulnerability where a malicious beneficiary can
  // create a proposal with wrong DSO that the delegate may unknowingly accept
  private def createMintingDelegationProposalWithCustomDso(
      beneficiaryParty: OnboardingResult,
      delegate: PartyId,
      expiresAt: java.time.Instant,
      dso: PartyId, // Can be any party, including invalid DSO
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    val beneficiary = beneficiaryParty.party
    val proposal = new mintingDelegationCodegen.MintingDelegationProposal(
      new mintingDelegationCodegen.MintingDelegation(
        beneficiary.toProtoPrimitive,
        delegate.toProtoPrimitive,
        dso.toProtoPrimitive, // Using provided DSO (can be wrong!)
        expiresAt,
        DefaultAmuletMergeLimit.toLong,
      )
    )
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        actingParty = beneficiaryParty.richPartyId,
        commands = proposal.create.commands.asScala.toSeq,
      )
  }

  private def createValidatorRight(
      externalParty: OnboardingResult
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    val validatorRight = new ValidatorRight(
      dsoParty.toProtoPrimitive,
      externalParty.party.toProtoPrimitive,
      externalParty.party.toProtoPrimitive,
    )
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        actingParty = externalParty.richPartyId,
        commands = validatorRight.create.commands.asScala.toSeq,
      )
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
}
