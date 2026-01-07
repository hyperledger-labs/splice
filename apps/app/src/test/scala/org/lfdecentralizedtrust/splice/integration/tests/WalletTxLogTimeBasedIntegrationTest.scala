package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  ExpiredAmuletTrigger,
  ExpiredLockedAmuletTrigger,
}
import org.lfdecentralizedtrust.splice.util.{
  EventId,
  SplitwellTestUtil,
  SvTestUtil,
  TriggerTestUtil,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
  TxLogEntry as walletLogEntry,
}
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.{
  AppRewardCoupon,
  ValidatorRewardCoupon,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.http.v0.definitions.DamlValueEncoding.members.CompactJson
import org.lfdecentralizedtrust.splice.http.v0.definitions.Transfer.TransferKind
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.TreeEvent
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItem

import java.time.Duration
import java.util.UUID

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
      .addConfigTransforms(
        (_, config) => ConfigTransforms.setAmuletPrice(amuletPrice)(config),
        // Sync up ExternalPartyConfigState and OpenMiningRound cycles to ensure we can consistently advance rounds for expiry.
        (_, config) =>
          ConfigTransforms.updateInitialExternalPartyConfigStateTickDuration(
            NonNegativeFiniteDuration.ofSeconds(600)
          )(config),
      )
  }

  override protected lazy val sanityChecksIgnoredRootCreates = Seq(
    AppRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
    ValidatorRewardCoupon.TEMPLATE_ID_WITH_PACKAGE_ID,
  )

  "A wallet" should {

    "handle app and validator rewards" taggedAs (org.lfdecentralizedtrust.splice.util.Tags.SpliceAmulet_0_1_14) in {
      implicit env =>
        val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
        waitForWalletUser(aliceValidatorWalletClient)
        waitForWalletUser(bobValidatorWalletClient)
        val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()

        clue("Tap to get some amulets") {
          aliceWalletClient.tap(100.0)
          aliceValidatorWalletClient.tap(100.0)
        }

        actAndCheck(
          "Alice transfers some CC to Bob and create rewards", {
            p2pTransfer(aliceWalletClient, bobWalletClient, bobUserParty, 40.0)
            createRewards(
              validatorRewards = Seq((aliceUserParty, 0.43)),
              appRewards = Seq((aliceValidatorParty, 0.43, false)),
            )
          },
        )(
          "Bob has received the CC",
          _ => bobWalletClient.balance().unlockedQty should be > BigDecimal(39.0),
        )

        // it takes 3 ticks for the IssuingMiningRound 1 to be created and open.
        clue("Advance rounds by 3 ticks.") {
          advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
          advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
          advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
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
          getRewardCouponsValue(appRewards, validatorRewards)

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
              logEntry.sender.value.amount should beWithin(
                -senderAmount - smallAmount,
                -senderAmount,
              )
              inside(logEntry.receivers) { case Seq(receiver) =>
                receiver.party shouldBe bobUserParty.toProtoPrimitive
                receiver.amount should beWithin(10 - smallAmount, BigDecimal(10))
              }
              logEntry.appRewardsUsed shouldBe appRewardAmount
              logEntry.validatorRewardsUsed shouldBe validatorRewardAmount
              logEntry.senderHoldingFees shouldBe BigDecimal(0)
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
            },
          ),
        )
    }

    "include correct fees" taggedAs (org.lfdecentralizedtrust.splice.util.Tags.SpliceAmulet_0_1_14) in {
      implicit env =>
        // Note: all of the parties in this test must be hosted on the same participant
        val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
        waitForWalletUser(aliceValidatorWalletClient)
        val aliceValidatorUserParty = aliceValidatorBackend.getValidatorPartyId()

        // Advance time to make sure we capture at least one round change in the tx history.
        val latestRound = eventuallySucceeds() {
          advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
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
          advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
          advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
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
              logEntry.senderHoldingFees shouldBe BigDecimal(0)
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
          activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredAmuletTrigger]),
      ) {
        actAndCheck(
          "Advance 5 ticks to expire the amulet", {
            Range(0, 5).foreach(_ =>
              advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
            )
          },
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
          activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger]),
      ) {
        actAndCheck(
          "Advance 5 ticks to expire the locked amulet", {
            Range(0, 5).foreach(_ =>
              advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)
            )
          },
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

    "Token Standard: expired locked amulet can be used as input, and txlog parses correctly" in {
      implicit env =>
        val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

        advanceRoundsToNextRoundOpening(synchronizeExternalPartyConfigStates = true)

        val expiryMinutes = 1L
        val tapAmount = 10_000L
        val lockAmount = 5_000L
        val transferAmount = 6_000L

        aliceWalletClient.tap(walletAmuletToUsd(tapAmount, amuletPrice))
        TriggerTestUtil.setTriggersWithin(
          triggersToPauseAtStart =
            // important that the holding is still locked so we can use it as input
            activeSvs.map(_.dsoDelegateBasedAutomation.trigger[ExpiredLockedAmuletTrigger]),
          Seq.empty,
        ) {
          val trackingId = UUID.randomUUID().toString

          actAndCheck(
            "lock the tapped amulet",
            lockAmulets(
              aliceValidatorBackend,
              aliceParty,
              aliceValidatorBackend.getValidatorPartyId(),
              aliceWalletClient.list().amulets,
              BigDecimal(lockAmount),
              sv1ScanBackend,
              java.time.Duration.ofMinutes(expiryMinutes),
              env.environment.clock.now,
            ),
          )(
            "Wait for locked amulet to appear",
            _ => {
              aliceWalletClient.list().lockedAmulets should have size (1)
            },
          )

          advanceTime(
            Duration
              .ofMinutes(expiryMinutes)
              // otherwise the time is equal to expiry and it doesn't get picked up, which is very confusing
              .plusSeconds(1L)
          )

          val offerResponse = aliceWalletClient.createTokenStandardTransfer(
            bobParty,
            transferAmount, // will need the locked amulet as input
            "transfer offer description",
            CantonTimestamp.now().plusSeconds(60),
            trackingId,
          )
          val offer = inside(offerResponse.output) {
            case members.TransferInstructionPending(value) =>
              new TransferInstruction.ContractId(value.transferInstructionCid)
          }

          val (aliceTxs, bobTxs) = clue("Alice and Bob parse all tx log entries") {
            eventually() {
              val aliceTxs = aliceWalletClient.listTransactions(None, 1000)
              // tap + lock + transfer instruction
              aliceTxs should have size 3
              val bobTxs = bobWalletClient.listTransactions(None, 1000)
              // transfer instruction
              bobTxs should have size 1
              (aliceTxs, bobTxs)
            }
          }

          checkTxHistory(
            aliceWalletClient,
            Seq(
              // create transfer instruction
              { case logEntry: TransferTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
                logEntry.transferInstructionCid shouldBe offer.contractId
                logEntry.transferInstructionReceiver shouldBe bobParty.toProtoPrimitive
                logEntry.transferInstructionAmount shouldBe Some(BigDecimal(transferAmount))
                logEntry.description shouldBe "transfer offer description"
                // No balance is transferred here so receivers is empty
                logEntry.receivers shouldBe empty
                val expectedExtraLock = BigDecimal(transferAmount - lockAmount)
                logEntry.sender.value.amount should beWithin(
                  -expectedExtraLock * 1.25,
                  -expectedExtraLock,
                )
              },
              // locking of 5k
              { case logEntry: TransferTxLogEntry =>
                // The `lockAmulets` utility function directly exercises the `AmuletRules_Transfer` choice.
                // This will appear as the catch-all "unknown transfer" in the history.
                logEntry.subtype.value shouldBe walletLogEntry.TransferTransactionSubtype.Transfer.toProto
                logEntry.transferInstructionCid shouldBe ""
                logEntry.transferInstructionAmount shouldBe None
                logEntry.description shouldBe ""
                // No balance is transferred here so receivers is empty
                logEntry.receivers shouldBe empty
                logEntry.sender.value.amount should beWithin(
                  -(lockAmount + smallAmount),
                  -lockAmount,
                )
              },
              { case logEntry: BalanceChangeTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                logEntry.amount shouldBe BigDecimal(tapAmount)
              },
            ),
          )

          // make sure that the OwnerExpireLock is in the update
          val createTransferUpdateId = inside(aliceTxs) {
            case (transfer: TransferTxLogEntry) +: _ =>
              EventId.updateIdFromEventId(transfer.eventId)
          }
          val createTransferUpdate = eventuallySucceeds() {
            sv1ScanBackend.getUpdate(createTransferUpdateId, CompactJson)
          }
          createTransferUpdate match {
            case UpdateHistoryItem.members.UpdateHistoryReassignment(_) =>
              fail("cannot be a reassignment")
            case UpdateHistoryItem.members.UpdateHistoryTransaction(value) =>
              inside(value.eventsById.values) { case trees =>
                forExactly(1, trees) {
                  case TreeEvent.members.ExercisedEvent(value) =>
                    value.choice should be(
                      splice.amulet.LockedAmulet.CHOICE_LockedAmulet_OwnerExpireLockV2.name
                    )
                  case _ => fail("irrelevant")
                }
              }
          }

          checkTxHistory(
            bobWalletClient,
            Seq(
              { case logEntry: TransferTxLogEntry =>
                logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
                logEntry.transferInstructionCid shouldBe offer.contractId
                logEntry.transferInstructionReceiver shouldBe bobParty.toProtoPrimitive
                logEntry.transferInstructionAmount shouldBe Some(BigDecimal(transferAmount))
                logEntry.description shouldBe "transfer offer description"
                logEntry.receivers shouldBe Seq(PartyAndAmount(bobParty.toProtoPrimitive, 0))
                logEntry.sender.value.amount shouldBe 0
              }
            ),
          )

          inside(sv1ScanBackend.listActivity(None, 10).flatMap(_.transfer)) { case transfers =>
            forExactly(1, transfers) { transfer =>
              transfer.transferKind shouldBe Some(TransferKind.CreateTransferInstruction)
              transfer.transferInstructionReceiver shouldBe Some(bobParty.toProtoPrimitive)
              transfer.sender.party shouldBe aliceParty.toProtoPrimitive
              BigDecimal(transfer.sender.senderChangeAmount) should be <= BigDecimal(
                tapAmount - transferAmount
              )
            }
          }
        }
    }
  }
}
