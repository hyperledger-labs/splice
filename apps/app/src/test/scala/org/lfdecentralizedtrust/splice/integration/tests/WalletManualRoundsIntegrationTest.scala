package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AppTransferContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.ans.{
  AnsEntryContext,
  AnsEntryContext_CollectInitialEntryPayment,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.Confirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AnsEntryContext
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ansentrycontext_actionrequiringconfirmation.ANSRARC_CollectInitialEntryPayment
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.subscriptions.{
  SubscriptionInitialPayment,
  SubscriptionRequest,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.sv.automation.confirmation.AnsSubscriptionInitialPaymentTrigger
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import org.lfdecentralizedtrust.splice.util.{
  Contract,
  SplitwellTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.ReceiveFaucetCouponTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletManualRoundsIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(splitwellDarPath)
      })
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          // without this, the test "generate app rewards correctly" has as flaky balance.
          // None of the other tests care about it.
          _.withPausedTrigger[ReceiveFaucetCouponTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          // without this, alice's validator gets AppRewardCoupons that complicate testing
          _.withPausedTrigger[ReceiveSvRewardCouponTrigger]
        )(config)
      )
      // Very short round ticks
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateInitialTickDuration(NonNegativeFiniteDuration.ofMillis(500))(config)
      )
      // Start rounds trigger in paused state
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
            .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
        )(config)
      )

  "A wallet" should {
    "list all amulets, including locked amulets, with additional position details" in {
      implicit env =>
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

        clue("Alice taps 50 amulets") {
          aliceWalletClient.list().amulets should have length 0
          aliceWalletClient.tap(50)
          eventually() {
            aliceWalletClient.list().amulets should have length 1
            aliceWalletClient.list().lockedAmulets should have length 0
          }
        }
        val startRound = aliceWalletClient.list().amulets.head.round
        val halfOriginalAmount =
          (walletUsdToAmulet(24), walletUsdToAmulet(25))

        lockAmulets(
          aliceValidatorBackend,
          aliceUserParty,
          aliceValidatorParty,
          aliceWalletClient.list().amulets,
          walletUsdToAmulet(25),
          sv1ScanBackend,
          Duration.ofHours(60),
          CantonTimestamp.now(),
        )

        clue("Check wallet after locking amulets") {
          aliceWalletClient.list().amulets should have length 1
          eventually()(aliceWalletClient.list().lockedAmulets should have length 1)

          aliceWalletClient.list().amulets.head.round shouldBe startRound
          // we have 0 holding fees because the amulets were created in the same round we are currently in
          aliceWalletClient.list().amulets.head.accruedHoldingFee shouldBe 0
          assertInRange(aliceWalletClient.list().amulets.head.effectiveAmount, halfOriginalAmount)

          aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound
          aliceWalletClient.list().lockedAmulets.head.accruedHoldingFee shouldBe 0
          assertInRange(
            aliceWalletClient.list().lockedAmulets.head.effectiveAmount,
            halfOriginalAmount,
          )
        }

        advanceRoundsByOneTickViaAutomation()

        clue("Check wallet after advancing to next round") {
          val expectedFee =
            (
              defaultHoldingFeeAmulet - walletUsdToAmulet(0.000001),
              defaultHoldingFeeAmulet + walletUsdToAmulet(0.000001),
            )
          eventually()(aliceWalletClient.list().amulets.head.round shouldBe startRound + 1)
          assertInRange(aliceWalletClient.list().amulets.head.accruedHoldingFee, expectedFee)
          assertInRange(aliceWalletClient.list().amulets.head.effectiveAmount, halfOriginalAmount)

          aliceWalletClient.list().lockedAmulets.head.round shouldBe startRound + 1
          assertInRange(
            aliceWalletClient.list().lockedAmulets.head.accruedHoldingFee,
            expectedFee,
          )
          assertInRange(
            aliceWalletClient.list().lockedAmulets.head.effectiveAmount,
            halfOriginalAmount,
          )
        }
    }

    "ensure balances are updated accordingly after a round" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceWalletClient.tap(50)
      val startingBalance = eventually() {
        val startingBalance = aliceWalletClient.balance()
        startingBalance.unlockedQty shouldBe walletUsdToAmulet(50.0)
        startingBalance
      }
      val lockedQty = walletUsdToAmulet(25)

      advanceRoundsByOneTickViaAutomation()

      eventuallySucceeds() {
        // The lockAmulets function is not atomic, it first fetches round data from Scan, and then
        // uses it to submit ledger transactions directly to the participant. It therefore might fail
        // when rounds are advancing. We do the same thing we expect an app to do, which is simply retry
        // on such failures.
        lockAmulets(
          aliceValidatorBackend,
          aliceUserParty,
          aliceValidatorParty,
          aliceWalletClient.list().amulets,
          lockedQty,
          sv1ScanBackend,
          Duration.ofHours(60),
          CantonTimestamp.now(),
        )
      }

      clue("Check balance after advancing round and locking amulets") {
        val feeCeiling = walletUsdToAmulet(1)
        eventually() {
          val expectedUnlockedAmulets = startingBalance.unlockedQty - lockedQty
          checkBalance(
            aliceWalletClient,
            Some(startingBalance.round + 1),
            (expectedUnlockedAmulets - feeCeiling, expectedUnlockedAmulets),
            (lockedQty - feeCeiling, lockedQty),
            (0, 1),
          )
        }
      }
    }

    "reject ans initial subscription payment due to expired round even if confirmed acceptance exists previously" in {
      implicit env =>
        def ansSubscriptionInitialPaymentTrigger =
          sv1Backend.dsoAutomation.trigger[AnsSubscriptionInitialPaymentTrigger]

        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        clue("Alice gets some amulets") {
          aliceWalletClient.tap(50)
        }
        val entryName = "alice"
        val (_, requestId) = actAndCheck(
          "Request ANS entry", {
            aliceAnsExternalClient
              .createAnsEntry(perTestCaseName(entryName), testEntryUrl, testEntryDescription)
          },
        )(
          "the corresponding subscription request is created",
          { _ =>
            inside(aliceWalletClient.listSubscriptionRequests()) { case Seq(r) =>
              r.contractId
            }
          },
        )

        // pause ansSubscriptionInitialPaymentTrigger so payment is not accepted
        ansSubscriptionInitialPaymentTrigger.pause().futureValue

        val (_, initialPayment) = actAndCheck(
          "Accept subscription request", {
            aliceWalletClient.acceptSubscriptionRequest(requestId)
          },
        )(
          "SubscriptionInitialPayment is created",
          _ => {
            aliceWalletClient.listSubscriptions() shouldBe empty
            inside(aliceWalletClient.listSubscriptionInitialPayments()) {
              case Seq(initialPayment) => initialPayment
            }
          },
        )

        val roundCid = sv1ScanBackend
          .getOpenAndIssuingMiningRounds()
          ._1
          .find(_.payload.round == initialPayment.payload.round)
          .value
          .contractId

        actAndCheck(
          "Advance rounds to make the round specified in SubscriptionInitialPayment closed", {
            (1 to 3).foreach(_ => advanceRoundsByOneTickViaAutomation())
          },
        )(
          s"The round $roundCid is closed",
          _ => {
            val openRounds = sv1Backend.listOpenMiningRounds().map(_.payload.round).toSet
            openRounds should not contain initialPayment.payload.round
          },
        )

        val transferContext = sv1ScanBackend.getUnfeaturedAppTransferContext(CantonTimestamp.now())
        val transferContextWithClosedRound = new AppTransferContext(
          transferContext.amuletRules,
          roundCid,
          transferContext.featuredAppRight,
        )
        actAndCheck(
          "Create a confirmation of accepting the ans initial payment with a closed round.", {
            createAnsAcceptInitialPaymentConfirmation(
              initialPayment,
              transferContextWithClosedRound,
            )
          },
        )(
          "The confirmation is created",
          _ => {
            lookupAnsAcceptInitialPaymentConfirmation(
              lookupAnsContextCid(initialPayment.payload.reference).value
            )
          },
        )

        loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.INFO))(
          {
            actAndCheck(
              "Resume ansSubscriptionInitialPaymentTrigger to check if it should accept payment", {
                ansSubscriptionInitialPaymentTrigger.resume()
              },
            )(
              "The payment is rejected due to the round for collecting payment is no longer active",
              _ => {
                aliceWalletClient.listSubscriptionInitialPayments() shouldBe empty
              },
            )
          },
          lines => {
            forExactly(1, lines) { line =>
              line.message should include regex (s"confirmed to reject payment.+Round\\(.+\\) is no longer active")
            }
          },
        )
    }

    "generate app rewards correctly" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      actAndCheck(
        "Grant featuredAppRight on Alice's wallet",
        grantFeaturedAppRight(aliceValidatorWalletClient),
      )(
        "Check that Alice's wallet is granted",
        _ => {
          aliceValidatorClient.getValidatorUserInfo().featured shouldBe true
        },
      )

      aliceWalletClient.tap(20.0)

      eventually() {
        aliceValidatorWalletClient.listAppRewardCoupons() should be(empty)
      }

      clue("Check that no payment requests exist") {
        aliceWalletClient.listAppPaymentRequests() shouldBe empty
      }

      actAndCheck(
        "Alice creates a self-payment request",
        createSelfPaymentRequest(
          aliceValidatorBackend.participantClientWithAdminToken,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
        ),
      )(
        "Wait for request to be ingested",
        _ => aliceWalletClient.listAppPaymentRequests() should have length 1,
      )

      val (_, rewardRound) = actAndCheck(
        "Alice accepts the payment request",
        aliceWalletClient
          .listAppPaymentRequests()
          .map(req => aliceWalletClient.acceptAppPaymentRequest(req.contractId)),
      )(
        "Request no longer exists and validator has one unfeatured app reward",
        _ => {
          aliceWalletClient.listAppPaymentRequests() should have length 0
          inside(aliceValidatorWalletClient.listAppRewardCoupons()) { case Seq(c) =>
            // Award for the first (locking) leg goes to the sender's validator
            // The wallet is not a featured app, so no featured app reward even if the validator party is featured!
            c.payload.featured shouldBe false
            c.payload.round
          }
        },
      )

      clue(s"Reward round $rewardRound is the newest open round") {
        val openRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._1
        openRounds.map(_.payload.round.number) should contain theSameElementsAs Seq(
          rewardRound.number - 2,
          rewardRound.number - 1,
          rewardRound.number,
        )
      }

      loggerFactory.assertEventuallyLogsSeq(
        SuppressionRule.LevelAndAbove(Level.INFO) && SuppressionRule.LoggerNameContains(
          "validator=aliceValidator"
        )
      )(
        {
          actAndCheck(
            "Advance rounds by 3 ticks",
            Seq(1, 2, 3).foreach(_ => advanceRoundsByOneTickViaAutomation()),
          )(
            s"Round $rewardRound is in issuing state",
            _ => {
              val issuingRounds = sv1ScanBackend.getOpenAndIssuingMiningRounds()._2
              issuingRounds.map(_.payload.round) should contain(rewardRound)
            },
          )
        },
        entries => {
          forAtLeast(1, entries) { line =>
            line.message should (include(
              "Not executing a merge operation because there no amulets to merge"
            ) and include("smaller than the create-fee"))
          }
        },
      )
    }
  }

  private def lookupAnsContextCid(
      subscriptionRequestCid: SubscriptionRequest.ContractId
  )(implicit env: SpliceTestConsoleEnvironment): Option[AnsEntryContext.ContractId] =
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(AnsEntryContext.COMPANION)(dsoParty)
      .find(_.data.reference == subscriptionRequestCid)
      .map(_.id)

  private def lookupAnsAcceptInitialPaymentConfirmation(
      ansContextCid: AnsEntryContext.ContractId
  )(implicit env: SpliceTestConsoleEnvironment): Option[Confirmation.ContractId] = {
    val svParty = sv1Backend.getDsoInfo().svParty
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(Confirmation.COMPANION)(dsoParty)
      .find(confirmation =>
        confirmation.data.action match {
          case ansContextAction: ARC_AnsEntryContext =>
            ansContextAction.ansEntryContextCid == ansContextCid && confirmation.data.confirmer == svParty.toProtoPrimitive
          case _ => false
        }
      )
      .map(_.id)
  }

  private def createAnsAcceptInitialPaymentConfirmation(
      initialPayment: Contract[SubscriptionInitialPayment.ContractId, SubscriptionInitialPayment],
      transferContext: AppTransferContext,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val svParty = sv1Backend.getDsoInfo().svParty
    val ansRules = sv1ScanBackend.getAnsRules()
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svParty),
      readAs = Seq(dsoParty),
      commands = sv1Backend
        .getDsoInfo()
        .dsoRules
        .contractId
        .exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          new ARC_AnsEntryContext(
            lookupAnsContextCid(initialPayment.payload.reference).value,
            new ANSRARC_CollectInitialEntryPayment(
              new AnsEntryContext_CollectInitialEntryPayment(
                initialPayment.contractId,
                transferContext,
                ansRules.contractId,
              )
            ),
          ),
        )
        .commands
        .asScala
        .toSeq,
    )
  }
}
