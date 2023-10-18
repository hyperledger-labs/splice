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

class ScanIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil {
  val defaultPageSize = 10000
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
      val tapsFirstPageAscending =
        sv1ScanBackend
          .listTransactions(None, TransactionHistoryRequest.SortOrder.Asc, pageSize)

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

      val bobTapAmount = 1000.0
      val aliceTapAmount = 1000.0

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

      val transferAmount = 100.0
      clue("Transfer some CC to alice")({
        p2pTransfer(
          bobValidatorBackend,
          bobWalletClient,
          aliceWalletClient,
          aliceUserParty,
          transferAmount,
        )
      })

      clue("Alice receives the transfer from bob")({
        eventually() {
          val balance = aliceWalletClient.balance()
          assertInRange(
            balance.unlockedQty,
            (aliceTapAmount + transferAmount - 1, aliceTapAmount + transferAmount),
          )
        }
        eventually() {
          val balance = bobWalletClient.balance()
          assertInRange(
            balance.unlockedQty,
            (bobTapAmount - transferAmount - 2, bobTapAmount - transferAmount),
          )
        }
      })

      eventually() {
        // only look at activities that bob sent to prevent flakes,
        // some activities may have occurred with the same SVC in previous tests
        val transfers =
          sv1ScanBackend.listActivity(None, 10).flatMap(_.transfer).filter { transfer =>
            PartyId.tryFromProtoPrimitive(
              transfer.sender.party
            ) == bobUserParty
          }

        transfers should have size (1)
        val transfer = transfers.head
        // bob transferred 100 + fees
        val inputCoinAmount =
          transfer.sender.inputCoinAmount.map(BigDecimal(_)).getOrElse(BigDecimal(0))

        BigDecimal(transfer.sender.senderChangeAmount) - inputCoinAmount should beWithin(
          BigDecimal(-1 * (transferAmount + 1.1)),
          BigDecimal(-1 * transferAmount),
        )
        // alice receives transfer
        transfer.receivers
          .map(r => BigDecimal(r.amount))
          .sum shouldBe BigDecimal(transferAmount)

        val taps = sv1ScanBackend.listActivity(None, 10).flatMap(_.tap).filter { tap =>
          PartyId.tryFromProtoPrimitive(tap.coinOwner) == bobUserParty
        }
        taps should have size (1)
        val tap = taps.head
        // bob tapped
        BigDecimal(tap.coinAmount) shouldBe (BigDecimal(bobTapAmount))
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
        val svRewardsCollected = sv1ScanBackend
          .listActivity(None, defaultPageSize)
          .flatMap(_.svRewardCollected)
        svRewardsCollected should have size (1)
        val svRewardCollected = svRewardsCollected(0)
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
        inputAppRewardAmounts should have size (1)
        val inputAppRewardAmount = inputAppRewardAmounts(0)
        inputAppRewardAmount shouldBe appRewardAmount

        val inputValidatorAmounts = bobTransfers
          .flatMap(_.sender.inputValidatorRewardAmount)
          .map(BigDecimal(_))
          .filter(_ != zero)
        inputValidatorAmounts should have size (1)
        val inputValidatorAmount = inputValidatorAmounts(0)
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
}
