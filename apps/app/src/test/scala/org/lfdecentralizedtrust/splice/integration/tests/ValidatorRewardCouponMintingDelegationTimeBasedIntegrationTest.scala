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

        // Advance rounds to ensure we have proper issuing rounds before creating coupons
        advanceRoundsToNextRoundOpening
        advanceRoundsToNextRoundOpening

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
            // Fund beneficiary1 so they can make a transfer
            fundExternalParty(beneficiary1, 30.0, "validator-to-beneficiary1")

            // Transfer from beneficiary1 to beneficiary2
            // This generates ValidatorRewardCoupons for beneficiary1 (the sender)
            // The transfer amount must be large enough (>15 amulets) so that the resulting
            // ValidatorRewardCoupon, when minted (coupon × 0.2 issuance rate), exceeds the
            // create fee (0.03). Otherwise the reward is entirely consumed by fees.
            transferBetweenExternalParties(
              beneficiary1,
              beneficiary2,
              BigDecimal(25.0),
              "beneficiary1-to-beneficiary2",
            )

            // Wait for beneficiary2 to receive the amulets
            eventually() {
              getExternalPartyBalance(beneficiary2.party).toDouble should be > 0.0
            }

            // Wait for coupons and advance rounds so coupon's round becomes issuing
            // CRITICAL: This must happen BEFORE unpausing the minting delegation trigger
            val couponRound = waitForCouponsAndAdvanceToIssuingRound(beneficiary1.party)
            logger.debug(s"Coupon round $couponRound is now issuing")
          }
          // Minting delegation trigger is now unpaused and will collect coupons
          // The coupon's round is now an issuing round, so the trigger should find it

          val prevBeneficiary1Balance = getExternalPartyBalance(beneficiary1.party)

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
              val newBeneficiary1Balance = getExternalPartyBalance(beneficiary1.party)
              logger.debug(
                s"Balance before: $prevBeneficiary1Balance, after: $newBeneficiary1Balance"
              )
              newBeneficiary1Balance should be > prevBeneficiary1Balance
            }
          }
        }
    }

    "filter out proposals with invalid DSO from wallet API (accidental protection)" in {
      implicit env =>
        // This test confirms that proposals created with an invalid DSO are NOT visible
        // via the wallet API methods. This is accidental protection due to DSO-based
        // scoping in multiDomainAcsStore, not intentional validation.
        //
        // The proposal EXISTS on the ledger but is filtered out by the wallet store.

        val (_, _) = onboardAliceAndBob()
        waitForWalletUser(aliceValidatorWalletClient)

        // Fund the validator wallet for external party setup
        aliceValidatorWalletClient.tap(100.0)

        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
        val externalParty = onboardExternalParty(aliceValidatorBackend, Some("filtered_party"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, externalParty)

        val expiresAt = env.environment.clock.now.plus(Duration.ofDays(30)).toInstant
        val fakeDso = externalParty.party // Use beneficiary's party as invalid DSO

        // Create proposal with invalid DSO
        createMintingDelegationProposal(
          externalParty,
          aliceValidatorParty,
          expiresAt,
          customDso = Some(fakeDso),
        )

        // First verify the proposal DOES exist on the ledger (direct ACS query)
        clue("Proposal should exist on ledger when queried directly") {
          eventually() {
            val acsProposals =
              aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .filterJava(mintingDelegationCodegen.MintingDelegationProposal.COMPANION)(
                  aliceValidatorParty,
                  _.data.delegation.dso == fakeDso.toProtoPrimitive,
                )
            acsProposals should have size 1
          }
        }

        // Now verify: Proposal is NOT visible via wallet API (filtered due to invalid DSO)
        clue("Proposal with invalid DSO should NOT be visible via wallet API") {
          val walletProposals = aliceValidatorWalletClient.listMintingDelegationProposals()
          walletProposals.proposals shouldBe empty
        }

        // Now create a valid proposal and verify it IS visible
        val validExternalParty =
          onboardExternalParty(aliceValidatorBackend, Some("valid_party"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, validExternalParty)

        createMintingDelegationProposal(
          validExternalParty,
          aliceValidatorParty,
          expiresAt,
          // No customDso = uses real DSO
        )

        clue("Proposal with valid DSO should be visible via wallet API") {
          eventually() {
            val walletProposals = aliceValidatorWalletClient.listMintingDelegationProposals()
            // The valid proposal should appear (size 1), while the invalid-DSO proposal
            // is filtered out. This confirms DSO-based filtering is working.
            walletProposals.proposals should have size 1
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
          createMintingDelegationProposal(
            maliciousBeneficiary,
            aliceValidatorParty,
            expiresAt,
            customDso = Some(fakeDso), // Wrong DSO!
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
        fundExternalParty(maliciousBeneficiary, 30.0, "funding-malicious-beneficiary")

        // Pause triggers to control timing
        val validatorRewardTrigger = collectRewardsAndMergeAmuletsTrigger(
          aliceValidatorBackend,
          aliceValidatorWalletClient.config.ledgerApiUser,
        )

        setTriggersWithin(triggersToPauseAtStart = Seq(validatorRewardTrigger)) {
          // Transfer from malicious beneficiary to recipient
          // This generates ValidatorRewardCoupons for malicious beneficiary (naturally created)
          transferBetweenExternalParties(
            maliciousBeneficiary,
            recipient,
            BigDecimal(25.0),
            "malicious-to-recipient",
          )

          // Wait for recipient to receive funds (confirms transfer completed)
          eventually() {
            getExternalPartyBalance(recipient.party).toDouble should be > 0.0
          }

          // Wait for coupons and advance rounds so coupon's round becomes issuing
          val couponRound = waitForCouponsAndAdvanceToIssuingRound(maliciousBeneficiary.party)

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

  /** Creates a MintingDelegationProposal for an external party beneficiary.
    * @param customDso If provided, uses this party as DSO (for testing invalid DSO scenarios).
    *                  If None, uses the real DSO party.
    */
  private def createMintingDelegationProposal(
      beneficiaryParty: OnboardingResult,
      delegate: PartyId,
      expiresAt: java.time.Instant,
      customDso: Option[PartyId] = None,
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    val beneficiary = beneficiaryParty.party
    val effectiveDso = customDso.getOrElse(dsoParty)
    val proposal = new mintingDelegationCodegen.MintingDelegationProposal(
      new mintingDelegationCodegen.MintingDelegation(
        beneficiary.toProtoPrimitive,
        delegate.toProtoPrimitive,
        effectiveDso.toProtoPrimitive,
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

  /** Transfers amulets between two external parties with proper signing. */
  private def transferBetweenExternalParties(
      sender: OnboardingResult,
      recipient: OnboardingResult,
      amount: BigDecimal,
      description: String,
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    val prepareSend = aliceValidatorBackend.prepareTransferPreapprovalSend(
      sender.party,
      recipient.party,
      amount,
      CantonTimestamp.now().plus(Duration.ofHours(1)),
      0L,
      Some(description),
    )
    aliceValidatorBackend.submitTransferPreapprovalSend(
      sender.party,
      prepareSend.transaction,
      HexString.toHexString(
        crypto(env.executionContext)
          .signBytes(
            HexString.parseToByteString(prepareSend.txHash).value,
            sender.privateKey.asInstanceOf[SigningPrivateKey],
            usage = SigningKeyUsage.ProtocolOnly,
          )
          .value
          .toProtoV30
          .signature
      ),
      publicKeyAsHexString(sender.publicKey),
    )
  }

  /** Funds an external party and waits for balance to be positive. */
  private def fundExternalParty(
      party: OnboardingResult,
      amount: Double,
      description: String,
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Unit = {
    aliceValidatorWalletClient.transferPreapprovalSend(party.party, amount, description)
    eventually() {
      getExternalPartyBalance(party.party).toDouble should be > 0.0
    }
  }

  /** Gets the unlocked coin balance for an external party. */
  private def getExternalPartyBalance(
      party: PartyId
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): BigDecimal = BigDecimal(
    aliceValidatorBackend.getExternalPartyBalance(party).totalUnlockedCoin
  )

  /** Waits for ValidatorRewardCoupons to exist and advances rounds until the coupon's round is issuing.
    * @return The round number of the coupon.
    */
  private def waitForCouponsAndAdvanceToIssuingRound(
      beneficiary: PartyId
  )(implicit env: SpliceTests.SpliceTestConsoleEnvironment): Long = {
    val couponRound = eventually(40.seconds) {
      val coupons =
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(ValidatorRewardCoupon.COMPANION)(
            beneficiary,
            _.data.user == beneficiary.toProtoPrimitive,
          )
      coupons should not be empty
      coupons.head.data.round.number
    }

    // Advance rounds so coupon's round becomes issuing
    advanceRoundsToNextRoundOpening
    advanceRoundsToNextRoundOpening
    advanceRoundsToNextRoundOpening

    // Verify coupon round is now issuing
    eventually() {
      val (_, issuingRounds) = sv1ScanBackend.getOpenAndIssuingMiningRounds()
      issuingRounds.map(_.payload.round.number).toSet should contain(couponRound)
    }

    couponRound
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
