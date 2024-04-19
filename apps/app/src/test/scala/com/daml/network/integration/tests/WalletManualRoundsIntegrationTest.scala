package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.amuletrules.AppTransferContext
import com.daml.network.codegen.java.splice.ans.{
  AnsEntryContext,
  AnsEntryContext_CollectInitialEntryPayment,
}
import com.daml.network.codegen.java.splice.dsorules.Confirmation
import com.daml.network.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AnsEntryContext
import com.daml.network.codegen.java.splice.dsorules.ansentrycontext_actionrequiringconfirmation.ANSRARC_CollectInitialEntryPayment
import com.daml.network.codegen.java.splice.wallet.subscriptions.{
  SubscriptionInitialPayment,
  SubscriptionRequest,
}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.config.CNNodeConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.sv.automation.confirmation.AnsSubscriptionInitialPaymentTrigger
import com.daml.network.sv.automation.leaderbased.AdvanceOpenMiningRoundTrigger
import com.daml.network.sv.automation.singlesv.ReceiveSvRewardCouponTrigger
import com.daml.network.util.{Contract, SplitwellTestUtil, TriggerTestUtil, WalletTestUtil}
import com.daml.network.validator.automation.ReceiveFaucetCouponTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import org.slf4j.event.Level

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletManualRoundsIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with SplitwellTestUtil
    with TriggerTestUtil {

  private val splitwellDarPath = "daml/splitwell/.daml/dist/splitwell-current.dar"
  private val testEntryUrl = "https://ans-dir-url.com"
  private val testEntryDescription = "Sample CNS Entry Description"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
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
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppFoundCollectiveConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      // Start rounds trigger in paused state
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Sv)(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
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

        advanceRoundsByOneTickViaAutomation

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

      advanceRoundsByOneTickViaAutomation

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
          "Request CNS entry", {
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
            (1 to 3).foreach(_ => advanceRoundsByOneTickViaAutomation)
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

        loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
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
      val tapRound = aliceWalletClient.balance().round

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

      actAndCheck(
        "Alice accepts the payment request",
        aliceWalletClient
          .listAppPaymentRequests()
          .map(req => aliceWalletClient.acceptAppPaymentRequest(req.contractId)),
      )(
        "Request no longer exists",
        _ => aliceWalletClient.listAppPaymentRequests() should have length 0,
      )

      actAndCheck(
        "Advance rounds until reward coupons are issued",
        Seq(1, 2).foreach(_ => advanceRoundsByOneTickViaAutomation),
      )(
        "Wait for reward coupons",
        _ => {
          aliceValidatorWalletClient
            .listAppRewardCoupons() should have length 1 // Award for the first (locking) leg goes to the sender's validator
        },
      )

      inside(aliceValidatorWalletClient.listAppRewardCoupons()) { case Seq(c) =>
        c.payload.featured shouldBe true
      }

      val balanceBefore = eventually() {
        val balance = aliceValidatorWalletClient.balance()
        balance.round shouldBe tapRound + 2
        balance
      }

      actAndCheck(
        "Advance rounds until reward coupons can be collected",
        Seq(1, 2).foreach(_ => advanceRoundsByOneTickViaAutomation),
      )(
        "Wait for reward to be collected",
        _ => {
          aliceValidatorWalletClient
            .listAppRewardCoupons() should be(empty)
          checkBalance(
            aliceValidatorWalletClient,
            Some(balanceBefore.round + 2),
            (
              balanceBefore.unlockedQty + 90.0,
              balanceBefore.unlockedQty + 90.5,
            ),
            (balanceBefore.lockedQty, balanceBefore.lockedQty),
            (0, 1),
          )
        },
      )
    }

    "issue app rewards for direct transfers to validator party" in { implicit env =>
      val (_, bobUserParty) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)

      val couponsBefore = aliceValidatorWalletClient.listAppRewardCoupons().length.toLong

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(500.0)
      }

      grantFeaturedAppRight(aliceValidatorWalletClient)
      p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
      eventually()({
        bobWalletClient.balance().unlockedQty should not be (BigDecimal(0.0))
        aliceValidatorWalletClient.listAppRewardCoupons() should have length (couponsBefore + 1)
        aliceValidatorWalletClient
          .listAppRewardCoupons()
          .lastOption
          .value
          .payload
          .featured shouldBe true
      })
    }

  }

  private def lookupAnsContextCid(
      subscriptionRequestCid: SubscriptionRequest.ContractId
  )(implicit env: CNNodeTestConsoleEnvironment): Option[AnsEntryContext.ContractId] =
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
      .filterJava(AnsEntryContext.COMPANION)(dsoParty)
      .find(_.data.reference == subscriptionRequestCid)
      .map(_.id)

  private def lookupAnsAcceptInitialPaymentConfirmation(
      ansContextCid: AnsEntryContext.ContractId
  )(implicit env: CNNodeTestConsoleEnvironment): Option[Confirmation.ContractId] = {
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
      env: CNNodeTestConsoleEnvironment
  ) = {
    val svParty = sv1Backend.getDsoInfo().svParty
    val ansRules = sv1ScanBackend.getAnsRules()
    sv1Backend.participantClientWithAdminToken.ledger_api_extensions.commands.submitJava(
      actAs = Seq(svParty),
      readAs = Seq(dsoParty),
      optTimeout = None,
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
