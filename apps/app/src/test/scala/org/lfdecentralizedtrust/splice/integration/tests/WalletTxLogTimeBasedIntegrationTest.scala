package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ExpiredAmuletTrigger,
  ExpiredLockedAmuletTrigger,
}
import org.lfdecentralizedtrust.splice.util.{
  SplitwellTestUtil,
  SvTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  TransferTxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext

import java.time.Duration

class WalletTxLogTimeBasedIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with SplitwellTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil
    with SvTestUtil {

  private val amuletPrice = BigDecimal(1.25).setScale(10)

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1SvWithSimTime(this.getClass.getSimpleName)
      // The wallet automation periodically merges amulets, which leads to non-deterministic balance changes.
      // We disable the automation for this suite.
      .withoutAutomaticRewardsCollectionAndAmuletMerging
      // Set a non-unit amulet price to better test CC-USD conversion.
      .addConfigTransform((_, config) => ConfigTransforms.setAmuletPrice(amuletPrice)(config))
  }

  "A wallet" should {

    "handle app and validator rewards" in { implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
      waitForWalletUser(aliceValidatorWalletClient)
      waitForWalletUser(bobValidatorWalletClient)

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(100.0)
        aliceValidatorWalletClient.tap(100.0)
      }

      actAndCheck(
        "Alice transfers some CC to Bob",
        p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0),
      )(
        "Bob has received the CC",
        _ => bobWalletClient.balance().unlockedQty should be > BigDecimal(39.0),
      )

      // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
      clue("Advance rounds by 3 ticks.") {
        advanceRoundsByOneTick
        advanceRoundsByOneTick
        advanceRoundsByOneTick
      }

      clue("Everyone still has their reward coupons") {
        eventually() {
          aliceValidatorWalletClient.listAppRewardCoupons() should have size 1
          aliceValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        }
      }

      val appRewards = aliceValidatorWalletClient.listAppRewardCoupons()
      val validatorRewards = aliceValidatorWalletClient.listValidatorRewardCoupons()
      val (appRewardAmount, validatorRewardAmount) =
        getRewardCouponsValue(appRewards, validatorRewards, false)

      actAndCheck(
        "Alice's validator transfers some CC to Bob (using her app & validator rewards)",
        p2pTransfer(aliceValidatorWalletClient, bobWalletClient, bobUserParty, 10.0),
      )(
        "Bob has received the CC",
        _ => {
          bobWalletClient.balance().unlockedQty should be > BigDecimal(49.0)
        },
      )

      checkTxHistory(
        bobWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: TransferTxLogEntry =>
            // Alice's validator sending 10CC to Bob, using their validator&app rewards and their amulet
            val senderAmount =
              (BigDecimal(10) - appRewardAmount - validatorRewardAmount) max BigDecimal(0)
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.P2PPaymentCompleted.toProto
            logEntry.sender.value.party shouldBe aliceValidatorBackend
              .getValidatorPartyId()
              .toProtoPrimitive
            logEntry.sender.value.amount should beWithin(-senderAmount - smallAmount, -senderAmount)
            inside(logEntry.receivers) { case Seq(receiver) =>
              receiver.party shouldBe bobUserParty.toProtoPrimitive
              receiver.amount should beWithin(10 - smallAmount, BigDecimal(10))
            }
            logEntry.appRewardsUsed shouldBe appRewardAmount
            logEntry.validatorRewardsUsed shouldBe validatorRewardAmount
            logEntry.senderHoldingFees should be > BigDecimal(0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
          { case logEntry: TransferTxLogEntry =>
            // Alice sending 40CC to Bob
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.P2PPaymentCompleted.toProto
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beWithin(-40 - smallAmount, -40)
            inside(logEntry.receivers) { case Seq(receiver) =>
              receiver.party shouldBe bobUserParty.toProtoPrimitive
              receiver.amount should beWithin(40 - smallAmount, 40)
            }
            logEntry.senderHoldingFees shouldBe BigDecimal(0)
            logEntry.amuletPrice shouldBe amuletPrice
          },
        ),
      )
    }

    "include correct fees" in { implicit env =>
      // Note: all of the parties in this test must be hosted on the same participant
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      waitForWalletUser(aliceValidatorWalletClient)
      val aliceValidatorUserParty = aliceValidatorBackend.getValidatorPartyId()

      // Advance time to make sure we capture at least one round change in the tx history.
      val latestRound = eventuallySucceeds() {
        advanceRoundsByOneTick
        sv1ScanBackend.getOpenAndIssuingMiningRounds()._1.last.contract.payload.round.number
      }

      val amuletConfig = clue("Get amulet config") {
        sv1ScanBackend.getAmuletConfigForRound(latestRound)
      }

      val transferAmount = BigDecimal("32.1234567891").setScale(10)
      val transferFee =
        amuletConfig.transferFee.steps
          .findLast(_.amount <= transferAmount)
          .fold(amuletConfig.transferFee.initial)(_.rate) * transferAmount
      val receiverFeeRatio = BigDecimal("0.1234567891").setScale(10)
      val senderFeeRatio = (BigDecimal(1.0) - receiverFeeRatio).setScale(10)

      clue("Tap to get some amulets") {
        aliceWalletClient.tap(10000)
      }

      clue("Advance rounds to accumulate holding fees") {
        advanceRoundsByOneTick
        advanceRoundsByOneTick
      }

      val balance0 = charlieWalletClient.balance().unlockedQty
      actAndCheck(
        "Alice makes a complex transfer",
        rawTransfer(
          aliceValidatorBackend,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          aliceValidatorBackend.getValidatorPartyId(),
          aliceWalletClient.list().amulets.head,
          Seq(
            transferOutputAmulet(
              charlieUserParty,
              receiverFeeRatio,
              transferAmount,
            ),
            transferOutputAmulet(
              charlieUserParty,
              receiverFeeRatio,
              transferAmount,
            ),
            transferOutputLockedAmulet(
              charlieUserParty,
              Seq(charlieUserParty, aliceValidatorUserParty),
              receiverFeeRatio,
              transferAmount,
              Duration.ofMinutes(5),
            ),
          ),
          getLedgerTime,
        ),
      )(
        "Charlie has received the CC",
        _ => charlieWalletClient.balance().unlockedQty should be > balance0,
      )

      val expectedAliceBalanceChange = BigDecimal(0) -
        3 * transferAmount - // each output is worth `transferAmount`
        3 * transferFee * senderFeeRatio - // one transferFee for each output
        3 * amuletConfig.amuletCreateFee * senderFeeRatio - // one amuletCreateFee for each output
        amuletConfig.lockHolderFee * senderFeeRatio - // one lockHolderFee for each lock holder that is not the receiver
        amuletConfig.amuletCreateFee // one amuletCreateFee for the sender change amulet

      val expectedCharlieBalanceChange = BigDecimal(0) +
        2 * transferAmount - // 2 amulets created for Charlie (the locked amulet does not change his balance)
        3 * transferFee * receiverFeeRatio - // one transferFee for each output
        3 * amuletConfig.amuletCreateFee * receiverFeeRatio - // one amuletCreateFee for each output
        amuletConfig.lockHolderFee * receiverFeeRatio // one lockHolderFee for each lock holder that is not the receiver

      // This test advances 2 rounds between the tap and the transfer
      val expectedHoldingFees = 2 * amuletConfig.holdingFee

      // Note: there are 3 places where we multiply fixed digit numbers:
      // - In our daml code, computing actual balance changes
      // - In the tx log parser, computing reported balance changes
      // - Here in the test, computing expected balance changes
      // Due to rounding, these numbers may all be different. We therefore only compare numbers up to 9 digits here.
      checkTxHistory(
        charlieWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: TransferTxLogEntry =>
            logEntry.sender.value.party shouldBe aliceUserParty.toProtoPrimitive
            logEntry.sender.value.amount should beEqualUpTo(expectedAliceBalanceChange, 9)
            logEntry.sender.value.amount.scale shouldBe scale
            inside(logEntry.receivers) { case Seq(receiver) =>
              receiver.party shouldBe charlieUserParty.toProtoPrimitive
              receiver.amount should beEqualUpTo(expectedCharlieBalanceChange, 9)
              receiver.amount.scale shouldBe scale
            }
            logEntry.senderHoldingFees shouldBe expectedHoldingFees
            logEntry.senderHoldingFees.scale shouldBe scale
          }
        ),
      )
    }

    "handle expired amulets in a transaction history" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

      actAndCheck(
        "Tap to get a small amulet",
        aliceWalletClient.tap(0.000005),
      )(
        "Wait for amulet to appear",
        _ => aliceWalletClient.list().amulets should have size (1),
      )

      setTriggersWithin(
        Seq.empty,
        triggersToResumeAtStart =
          Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger]),
      ) {
        actAndCheck(
          "Advance 4 ticks to expire the amulet",
          Range(0, 4).foreach(_ => advanceRoundsByOneTick),
        )(
          "Wait for amulet to disappear",
          _ => aliceWalletClient.list().amulets should have size (0),
        )
      }

      checkTxHistory(
        aliceWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.AmuletExpired.toProto
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          },
        ),
      )

    }

    "handle expired locked amulets in a transaction history" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

      actAndCheck(
        "Tap to get some amulet",
        aliceWalletClient.tap(100),
      )(
        "Wait for amulet to appear",
        _ => aliceWalletClient.list().amulets should have size (1),
      )

      actAndCheck(
        "Lock a small amulet",
        lockAmulets(
          aliceValidatorBackend,
          aliceParty,
          aliceValidatorParty,
          aliceWalletClient.list().amulets,
          BigDecimal(0.000005),
          sv1ScanBackend,
          java.time.Duration.ofMinutes(5),
          getLedgerTime,
        ),
      )(
        "Wait for locked amulet to appear",
        _ => aliceWalletClient.list().lockedAmulets should have size (1),
      )

      setTriggersWithin(
        Seq.empty,
        triggersToResumeAtStart =
          Seq(sv1Backend.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger]),
      ) {
        actAndCheck(
          "Advance 4 ticks to expire the locked amulet",
          Range(0, 4).foreach(_ => advanceRoundsByOneTick),
        )(
          "Wait for locked amulet to disappear",
          _ => aliceWalletClient.list().lockedAmulets should have size (0),
        )
      }

      checkTxHistory(
        aliceWalletClient,
        Seq[CheckTxHistoryFn](
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.LockedAmuletExpired.toProto
          },
          { case logEntry: TransferTxLogEntry =>
            // The `lockAmulets` utility function directly exercises the `AmuletRules_Transfer` choice.
            // This will appear as the catch-all "unknown transfer" in the history.
            logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.Transfer.toProto
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe walletLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          },
        ),
      )
    }
  }
}
