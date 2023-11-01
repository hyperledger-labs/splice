package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.round.OpenMiningRound
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.*
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.daml.network.wallet.automation.CollectRewardsAndMergeCoinsTrigger
import com.daml.network.sv.automation.leaderbased.AdvanceOpenMiningRoundTrigger
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.daml.network.http.v0.definitions.TransactionHistoryRequest
import com.daml.network.http.v0.definitions.TransactionHistoryResponseItem
import com.daml.network.integration.plugins.UseInMemoryStores
import com.daml.network.store.Limit

import scala.math.BigDecimal.javaBigDecimal2bigDecimal

class InMemoryScanIntegrationTest extends ScanIntegrationTest {
  registerPlugin(new UseInMemoryStores(loggerFactory))
}

class ScanIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil {
  private val defaultPageSize = Limit.MaxPageSize
  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      // start only sv1 but not sv2-4, to speed up the test
      .addConfigTransformsToFront(
        CNNodeConfigTransforms.onlySv1,
        { case (_, c) => CNNodeConfigTransforms.ingestFromParticipantBeginInScan(c) },
      )
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.withPausedTrigger[CollectRewardsAndMergeCoinsTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllAutomationConfigs(
          _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        CNNodeConfigTransforms.updateAllSvAppFoundCollectiveConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      .withTrafficTopupsDisabled

  "list transaction pages in ascending and descending order" in { implicit env =>
    onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val nrTaps = 10
    val coinAmounts = (1 to nrTaps).toSeq.map(BigDecimal(_))
    val pageSize = nrTaps / 2

    def toCoinAmounts(page: Seq[TransactionHistoryResponseItem]) =
      page.flatMap(_.tap.map(t => BigDecimal(t.coinAmount)))

    actAndCheck(
      "Tap coins for Alice", {
        (1 to nrTaps).foreach { i =>
          aliceWalletClient.tap(BigDecimal(i))
        }
      },
    )(
      "Coins should appear in Alice's wallet",
      _ => {
        aliceWalletClient.list().coins should have length nrTaps.toLong
      },
    )

    eventually() {
      val latestRound =
        sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

      val tapsFirstPageAscending =
        sv1ScanBackend
          .listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, pageSize)

      tapsFirstPageAscending.map(_.round) should contain only latestRound

      toCoinAmounts(tapsFirstPageAscending) should be(
        coinAmounts.take(pageSize)
      )

      val firstPageEndEventId = tapsFirstPageAscending.last.eventId
      val tapsSecondPageAscending =
        sv1ScanBackend
          .listTransactions(
            Some(firstPageEndEventId),
            TransactionHistoryRequest.SortOrder.Asc,
            pageSize.toInt,
          )

      toCoinAmounts(tapsSecondPageAscending) should be(
        coinAmounts.drop(pageSize).take(pageSize)
      )

      sv1ScanBackend
        .listTransactions(
          Some(tapsSecondPageAscending.last.eventId),
          TransactionHistoryRequest.SortOrder.Asc,
          pageSize.toInt,
        ) should be(empty)

      val tapsFirstPageDescending =
        sv1ScanBackend
          .listTransactions(
            None,
            TransactionHistoryRequest.SortOrder.Desc,
            pageSize.toInt,
          )
      toCoinAmounts(tapsFirstPageDescending) should be(
        coinAmounts.reverse.take(pageSize)
      )

      val firstPageEndEventIdDescending = tapsFirstPageDescending.last.eventId
      val tapsSecondPageDescending =
        sv1ScanBackend
          .listTransactions(
            Some(firstPageEndEventIdDescending),
            TransactionHistoryRequest.SortOrder.Desc,
            pageSize.toInt,
          )

      sv1ScanBackend
        .listTransactions(
          Some(tapsSecondPageDescending.last.eventId),
          TransactionHistoryRequest.SortOrder.Desc,
          pageSize.toInt,
        ) should be(empty)

      toCoinAmounts(tapsSecondPageDescending) should be(
        coinAmounts.reverse.drop(pageSize).take(pageSize)
      )
      toCoinAmounts(
        tapsFirstPageAscending ++ tapsSecondPageAscending
      ) should be(toCoinAmounts((tapsFirstPageDescending ++ tapsSecondPageDescending).reverse))
    }
  }

  "list tap and coin merge transactions" in { implicit env =>
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

    def aliceMergeCoinsTrigger =
      aliceValidatorBackend
        .userWalletAutomation(aliceUserName)
        .trigger[CollectRewardsAndMergeCoinsTrigger]

    aliceMergeCoinsTrigger.pause().futureValue
    val tap1 = 40.0
    val tap2 = 60.0
    val aliceCoin = tap1 + tap2
    actAndCheck(
      "Tap 2 coins for Alice", {
        aliceWalletClient.tap(tap1)
        aliceWalletClient.tap(tap2)
      },
    )(
      "Coins should appear in Alice's wallet",
      _ => aliceWalletClient.list().coins should have length 2,
    )

    actAndCheck(
      "Run merge coins automation once",
      aliceMergeCoinsTrigger.runOnce().futureValue,
    )(
      "Verify that coins were merged",
      workDone => {
        workDone should be(true)
        aliceWalletClient.list().coins should have length 1
      },
    )

    eventually() {
      val taps = sv1ScanBackend.listActivity(None, 10).flatMap(_.tap).filter { tap =>
        PartyId.tryFromProtoPrimitive(
          tap.coinOwner
        ) == aliceUserParty
      }
      taps should have size (2)
      taps.map(t => (PartyId.tryFromProtoPrimitive(t.coinOwner), BigDecimal(t.coinAmount))) shouldBe
        Vector((aliceUserParty, BigDecimal(tap2)), (aliceUserParty, BigDecimal(tap1)))

      val merges =
        sv1ScanBackend.listActivity(None, 10).flatMap(_.transfer).filter { transfer =>
          PartyId.tryFromProtoPrimitive(
            transfer.sender.party
          ) == aliceUserParty && transfer.receivers.isEmpty
        }

      merges should have size (1)
      val mergeTransfer = merges.head
      PartyId.tryFromProtoPrimitive(mergeTransfer.sender.party) shouldBe aliceUserParty
      mergeTransfer.sender.inputCoinAmount
        .map(BigDecimal(_))
        .getOrElse(BigDecimal(0)) shouldBe (BigDecimal(aliceCoin))
      BigDecimal(mergeTransfer.sender.senderChangeAmount) shouldBe (BigDecimal(
        aliceCoin
      ) - BigDecimal(mergeTransfer.sender.senderChangeFee) - BigDecimal(
        mergeTransfer.sender.holdingFees
      ) - BigDecimal(mergeTransfer.sender.senderFee))
    }
  }

  "list recent transfers and taps, with rewards collection and coin merging turned off" in {
    implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val aliceUserName = aliceWalletClient.config.ledgerApiUser
      val bobUserName = bobWalletClient.config.ledgerApiUser

      def aliceMergeCoinsTrigger =
        aliceValidatorBackend
          .userWalletAutomation(aliceUserName)
          .trigger[CollectRewardsAndMergeCoinsTrigger]

      aliceMergeCoinsTrigger.pause().futureValue

      def bobMergeCoinsTrigger =
        bobValidatorBackend
          .userWalletAutomation(bobUserName)
          .trigger[CollectRewardsAndMergeCoinsTrigger]

      bobMergeCoinsTrigger.pause().futureValue

      val latestRound =
        sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

      val coinConfig =
        sv1ScanBackend.getCoinConfigForRound(latestRound)

      val bobTapAmount = 100000.0
      val aliceTapAmount = 100000.0

      actAndCheck(
        "Tap coins for Alice and bob", {
          aliceWalletClient.tap(aliceTapAmount)
          bobWalletClient.tap(bobTapAmount)
        },
      )(
        "Coins should appear in Alice and Bob's wallet",
        _ => {
          aliceWalletClient.list().coins should have length 1
          bobWalletClient.list().coins should have length 1
        },
      )
      val openRoundForTransfer =
        sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

      val transferAmount = BigDecimal(10000.0)
      clue("Transfer some CC to alice") {
        p2pTransfer(
          bobValidatorBackend,
          bobWalletClient,
          aliceWalletClient,
          aliceUserParty,
          transferAmount,
        )
      }

      clue("Alice receives the transfer from bob") {
        eventually() {
          val balance = aliceWalletClient.balance()
          balance.unlockedQty shouldBe (aliceTapAmount + transferAmount)
        }
        eventually() {
          val approxBobFees = 3 // this value depends on transferAmount
          val balance = bobWalletClient.balance()
          assertInRange(
            balance.unlockedQty,
            (bobTapAmount - transferAmount - approxBobFees, bobTapAmount - transferAmount),
          )
        }
      }
      val bobBalanceAfterTransfer = bobWalletClient.balance()

      clue("The transfer from Bob to Alice is shown in scan activity") {
        eventually() {
          // only look at activities that bob sent
          val activities =
            sv1ScanBackend
              .listActivity(None, 10)
              .filter(_.transfer.exists { transfer =>
                PartyId.tryFromProtoPrimitive(
                  transfer.sender.party
                ) == bobUserParty
              })

          activities should have size (1)
          activities.loneElement.round shouldBe openRoundForTransfer
          val transfer = activities.flatMap(_.transfer).loneElement
          val inputCoinAmount =
            transfer.sender.inputCoinAmount.map(BigDecimal(_)).getOrElse(BigDecimal(0))
          val senderChangeFee = BigDecimal(transfer.sender.senderChangeFee)
          senderChangeFee shouldBe (coinConfig.coinCreateFee)
          val senderFee = BigDecimal(transfer.sender.senderFee)
          val holdingFees = BigDecimal(transfer.sender.holdingFees)
          val senderChangeAmount = BigDecimal(transfer.sender.senderChangeAmount)

          senderFee shouldBe expectedSenderFee(transferAmount)

          val totalSenderFee = senderFee + holdingFees + senderChangeFee

          inputCoinAmount - senderChangeAmount shouldBe (transferAmount + totalSenderFee)

          // alice receives transfer
          transfer.receivers
            .map(r => BigDecimal(r.amount))
            .sum shouldBe transferAmount

          // receiverFee is by default set to 0, sender pays all fees.
          transfer.receivers
            .map(r => BigDecimal(r.receiverFee)) should contain only (BigDecimal(0))

          val taps = sv1ScanBackend.listActivity(None, 10).flatMap(_.tap).filter { tap =>
            PartyId.tryFromProtoPrimitive(tap.coinOwner) == bobUserParty
          }
          taps should have size (1)
          val tap = taps.head
          // bob tapped
          BigDecimal(tap.coinAmount) shouldBe (BigDecimal(bobTapAmount))
        }
      }
      val selfTransferAmount = BigDecimal(1000)
      clue("Self Transfer some CC from/to Bob") {
        p2pTransfer(
          bobValidatorBackend,
          bobWalletClient,
          bobWalletClient,
          bobUserParty,
          selfTransferAmount,
        )
      }
      clue("Bob receives self-transfer") {
        eventually() {
          // a self-transfer should cost some fees.
          val balance = bobWalletClient.balance()
          balance.unlockedQty should be < bobBalanceAfterTransfer.unlockedQty
        }
      }
      clue("Bob's self-transfer is shown in scan activity") {
        eventually() {
          // only check bob's self transfer
          val transfers =
            sv1ScanBackend.listActivity(None, 10).flatMap(_.transfer).filter { transfer =>
              PartyId.tryFromProtoPrimitive(
                transfer.sender.party
              ) == bobUserParty &&
              transfer.receivers.size == 1 && PartyId
                .tryFromProtoPrimitive(transfer.receivers.head.party) == bobUserParty
            }
          transfers should have size (1)
          val transfer = transfers.head
          val receiver = transfer.receivers.loneElement
          PartyId
            .tryFromProtoPrimitive(receiver.party) shouldBe (bobUserParty)
          val inputCoinAmount =
            transfer.sender.inputCoinAmount.map(BigDecimal(_)).getOrElse(BigDecimal(0))
          val senderChangeFee = BigDecimal(transfer.sender.senderChangeFee)
          senderChangeFee shouldBe (coinConfig.coinCreateFee)

          val senderFee = BigDecimal(transfer.sender.senderFee)
          val holdingFees = BigDecimal(transfer.sender.holdingFees)
          val senderChangeAmount = BigDecimal(transfer.sender.senderChangeAmount)
          senderFee shouldBe BigDecimal(CNNodeUtil.defaultCreateFee.fee)

          val totalSenderFee = senderFee + holdingFees + senderChangeFee
          inputCoinAmount - senderChangeAmount shouldBe (selfTransferAmount + totalSenderFee)

          BigDecimal(receiver.amount) shouldBe selfTransferAmount
          BigDecimal(receiver.receiverFee) shouldBe BigDecimal(0)
        }
      }

      val balance0 = charlieWalletClient.balance().unlockedQty
      val receiverFeeRatio = BigDecimal("0.5")
      actAndCheck(
        "Alice makes a transfer with a non-zero receiverFeeRatio",
        rawTransfer(
          aliceValidatorBackend,
          aliceWalletClient.config.ledgerApiUser,
          aliceUserParty,
          aliceValidatorBackend.getValidatorPartyId(),
          aliceWalletClient.list().coins.head,
          Seq(
            transferOutputCoin(
              charlieUserParty,
              receiverFeeRatio,
              transferAmount,
            )
          ),
          CantonTimestamp.now(),
        ),
      )(
        "Charlie has received the CC",
        _ => charlieWalletClient.balance().unlockedQty should be > balance0,
      )
      clue("Alice's transfer to Charlie shows receiver fees according to receiverFeeRatio set") {
        eventually() {
          // only check bob's self transfer
          val transfers =
            sv1ScanBackend.listActivity(None, 10).flatMap(_.transfer).filter { transfer =>
              PartyId.tryFromProtoPrimitive(
                transfer.sender.party
              ) == aliceUserParty &&
              transfer.receivers.size == 1 && PartyId
                .tryFromProtoPrimitive(transfer.receivers.head.party) == charlieUserParty
            }
          transfers should have size (1)
          val transfer = transfers.head
          val receiver = transfer.receivers.loneElement
          PartyId
            .tryFromProtoPrimitive(receiver.party) shouldBe (charlieUserParty)
          BigDecimal(receiver.amount) shouldBe transferAmount
          BigDecimal(receiver.receiverFee) shouldBe expectedSenderFee(
            transferAmount
          ) * receiverFeeRatio
        }
      }
  }

  "list collected app and validator and SV rewards" in { implicit env =>
    val (alice, _) = onboardAliceAndBob()
    waitForWalletUser(aliceValidatorWalletClient)
    waitForWalletUser(bobValidatorWalletClient)
    val bobValidatorUserName = bobValidatorWalletClient.config.ledgerApiUser
    val bobValidatorParty = bobValidatorBackend.getValidatorPartyId().toProtoPrimitive

    // The current round, as seen by the SV1 scan service (reflects the state of the scan app store)
    def currentRoundInScan(): Long =
      sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

    // The current round, as seen by the SV1 participant
    def currentRoundOnLedger(): Long =
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(OpenMiningRound.COMPANION)(svcParty)
        .map(_.data.round.number)
        .max

    clue("Ensuring Scan has ingested initial rounds") {
      // There are exactly 2 initial rounds created when bootstrapping the network.
      // This is performed synchronously, so we know for sure that the initial rounds
      // exist on the ledger.
      val round = currentRoundOnLedger()

      // Although very unlikely, the scan app store might not have ingested the initial rounds
      // at this point yet.
      eventually() {
        currentRoundInScan() should be(round)
      }
    }

    def bobValidatorRewardsTrigger =
      bobValidatorBackend
        .userWalletAutomation(bobValidatorUserName)
        .trigger[CollectRewardsAndMergeCoinsTrigger]
    bobValidatorRewardsTrigger.resume()

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated (when the sv leader changes)
    def advanceTrigger = sv1Backend.leaderBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

    clue("Bob grants featured app and taps, transfers to alice") {
      grantFeaturedAppRight(bobValidatorWalletClient)

      bobWalletClient.tap(50)
      actAndCheck(
        "Transfer from Bob to Alice",
        p2pTransfer(bobValidatorBackend, bobWalletClient, aliceWalletClient, alice, 30.0),
      )(
        "Bob's validator will receive some rewards",
        _ => {
          bobValidatorWalletClient.listAppRewardCoupons() should have size 1
          bobValidatorWalletClient.listValidatorRewardCoupons() should have size 1
        },
      )
    }
    val appRewardCoupons = bobValidatorWalletClient.listAppRewardCoupons()
    val validatorRewardCoupons = bobValidatorWalletClient.listValidatorRewardCoupons()

    clue("Advancing round and checking sv reward") {
      eventually() {
        advanceTrigger.runOnce().futureValue should be(true)
      }

      eventually() {
        val latestRound =
          sv1ScanBackend
            .getLatestOpenMiningRound(CantonTimestamp.now())
            .contract
            .payload
            .round
            .number
        val activities = sv1ScanBackend
          .listActivity(None, defaultPageSize)
          .filter(_.svRewardCollected.nonEmpty)
        activities.loneElement.round shouldBe latestRound
        val svRewardsCollected = activities.flatMap(_.svRewardCollected)
        val svRewardCollected = svRewardsCollected.loneElement
        val balance = sv1WalletClient.balance().unlockedQty
        BigDecimal(svRewardCollected.coinAmount) shouldBe balance
        svRewardCollected.coinOwner shouldBe sv1Backend.getSvcInfo().svParty.toProtoPrimitive
      }
    }

    clue("Trigger automation 2 more times to advance 3 rounds") {
      (1 to 2).foreach { _ =>
        eventually() {
          advanceTrigger.runOnce().futureValue should be(true)
        }
      }
    }

    val (appRewardAmount, validatorRewardAmount) =
      getRewardCouponsValue(appRewardCoupons, validatorRewardCoupons, true)

    clue("Checking app and validator reward amounts") {
      eventually() {
        bobValidatorWalletClient.listAppRewardCoupons() should have size 0
        bobValidatorWalletClient.listValidatorRewardCoupons() should have size 0

        val zero = BigDecimal(0)
        val bobTransfers = sv1ScanBackend
          .listActivity(None, defaultPageSize)
          .flatMap(_.transfer)
          .filter(_.sender.party == bobValidatorParty)

        val inputAppRewardAmounts = bobTransfers
          .flatMap(_.sender.inputAppRewardAmount)
          .map(BigDecimal(_))
          .filter(_ != zero)
        val inputAppRewardAmount = inputAppRewardAmounts.loneElement
        inputAppRewardAmount shouldBe appRewardAmount

        val inputValidatorAmounts = bobTransfers
          .flatMap(_.sender.inputValidatorRewardAmount)
          .map(BigDecimal(_))
          .filter(_ != zero)
        val inputValidatorAmount = inputValidatorAmounts.loneElement
        inputValidatorAmount shouldBe validatorRewardAmount
      }
    }
  }

  "list minted coins" in { implicit env =>
    val sv1UserParty = onboardWalletUser(sv1WalletClient, sv1ValidatorBackend)
    val mintAmount = 47.0
    clue("Mint to get some coins") {
      actAndCheck(
        "sv1 mints coins", {
          mintCoin(
            sv1ValidatorBackend.participantClientWithAdminToken,
            sv1UserParty,
            mintAmount,
          )
        },
      )(
        "Coins should appear in sv1's wallet",
        _ => {
          sv1WalletClient.list().coins should have length 1
          sv1WalletClient.list().coins.loneElement.effectiveAmount should be(BigDecimal(mintAmount))
        },
      )
    }
    eventually() {
      val sv1Mints = sv1ScanBackend
        .listActivity(None, defaultPageSize)
        .flatMap(_.mint)
        .filter(_.coinOwner == sv1UserParty.toProtoPrimitive)
      BigDecimal(sv1Mints.loneElement.coinAmount) shouldBe BigDecimal(mintAmount)
      val sv1MintsFromHistory = sv1ScanBackend
        .listTransactions(None, TransactionHistoryRequest.SortOrder.Desc, defaultPageSize)
        .flatMap(_.mint)
        .filter(_.coinOwner == sv1UserParty.toProtoPrimitive)
      BigDecimal(sv1MintsFromHistory.loneElement.coinAmount) shouldBe BigDecimal(mintAmount)
    }
  }

  def expectedSenderFee(amount: BigDecimal) = {
    require(amount > 1000 && amount < 100000)
    val initialRate = CNNodeUtil.defaultTransferFee.initialRate
    val step1 = CNNodeUtil.defaultTransferFee.steps.get(0)
    val step2 = CNNodeUtil.defaultTransferFee.steps.get(1)
    val step3 = CNNodeUtil.defaultTransferFee.steps.get(2)
    val (step1Amount, step1Mult) = (step1._1, step1._2)
    val (step2Amount, step2Mult) = (step2._1, step2._2)
    // ensuring the right steps are hardcoded here.
    BigDecimal(step3._1) should be > amount
    val remainder = amount - step1Amount - step2Amount
    // this is specific for the transferAmount > step3._1
    val steppedRate =
      initialRate * step1Amount + step1Mult * step2Amount +
        step2Mult
        * remainder
    CNNodeUtil.defaultCreateFee.fee + steppedRate
  }
}
