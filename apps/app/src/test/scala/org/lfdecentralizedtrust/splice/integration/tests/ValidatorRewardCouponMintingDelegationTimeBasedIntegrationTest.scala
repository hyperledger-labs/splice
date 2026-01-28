package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.HexString
import com.digitalasset.canton.crypto.{SigningKeyUsage, SigningPrivateKey}
import org.lfdecentralizedtrust.splice.automation.Trigger
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  ValidatorRewardCoupon,
  ValidatorRight,
}
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
