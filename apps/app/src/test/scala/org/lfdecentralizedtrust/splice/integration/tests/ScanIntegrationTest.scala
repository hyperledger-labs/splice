package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.round.OpenMiningRound
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import ConfigTransforms.{ConfigurableApp, updateAutomationConfig}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvNodeState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.*
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.{
  AdvanceOpenMiningRoundTrigger,
  ExpireIssuingMiningRoundTrigger,
}
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  BalanceChange,
  TransactionHistoryRequest,
  TransactionHistoryResponseItem,
}
import org.lfdecentralizedtrust.splice.store.Limit
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvAppClient

import scala.math.BigDecimal.javaBigDecimal2bigDecimal
import org.lfdecentralizedtrust.splice.validator.automation.TopupMemberTrafficTrigger
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.{Get, Post}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.lfdecentralizedtrust.splice.scan.config.BftSequencerConfig

import scala.concurrent.{Future, blocking}
import scala.util.{Success, Try}

class ScanIntegrationTest extends IntegrationTest with WalletTestUtil with TimeTestUtil {
  private val defaultPageSize = Limit.MaxPageSize
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        (updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        ) andThen
          updateAutomationConfig(ConfigurableApp.Sv)(
            _.withPausedTrigger[AdvanceOpenMiningRoundTrigger]
              .withPausedTrigger[ExpireIssuingMiningRoundTrigger]
          ))(config)
      )
      .addConfigTransform((_, config) =>
        ConfigTransforms.updateAllScanAppConfigs_(config =>
          config.copy(
            bftSequencers = Seq(
              BftSequencerConfig(
                config.domainMigrationId,
                config.sequencerAdminClient,
                "http://testUrl:8081",
              )
            ),
            parameters =
              config.parameters.copy(customTimeouts = config.parameters.customTimeouts.map {
                // guaranteeing a timeout for first test below
                case (key @ "getAcsSnapshot", _) =>
                  key -> NonNegativeFiniteDuration.ofMillis(1L)
                case other => other
              }),
          )
        )(config)
      )
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialTickDuration = NonNegativeFiniteDuration.ofMillis(500))
        )(config)
      )
      .withTrafficTopupsEnabled

  "getAcsSnapshot respects custom timeout" in { implicit env =>
    loggerFactory.assertLogsUnordered(
      Try(sv1ScanBackend.getAcsSnapshot(dsoParty, None)).isFailure should be(true),
      _.warningMessage should include("resulted in a timeout after 1 millisecond"),
      _.errorMessage should include(
        "Command failed, message: The server is taking too long to respond to the request"
      ),
    )
  }

  "return dso info same as the sv app" in { implicit env =>
    val scan = sv1ScanBackend.getDsoInfo()
    inside(sv1Backend.getDsoInfo()) {
      case HttpSvAppClient.DsoInfo(
            svUser,
            svParty,
            dsoParty,
            votingThreshold,
            latestMiningRound,
            amuletRules,
            dsoRules,
            svNodeStates,
          ) =>
        scan.svUser should be(svUser)
        scan.svPartyId should be(svParty.toProtoPrimitive)
        scan.dsoPartyId should be(dsoParty.toProtoPrimitive)
        scan.votingThreshold should be(votingThreshold)
        scan.latestMiningRound should be(latestMiningRound.toHttp)
        scan.amuletRules should be(amuletRules.toHttp)
        scan.dsoRules should be(dsoRules.toHttp)
        scan.svNodeStates should be(svNodeStates.map(_._2.toHttp))
    }
    // sanity checks
    scan.dsoRules.contract.contractId should be(
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(DsoRules.COMPANION)(dsoParty)
        .loneElement
        .id
        .contractId
    )
    scan.amuletRules.contract.contractId should be(
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(AmuletRules.COMPANION)(dsoParty)
        .loneElement
        .id
        .contractId
    )
    scan.svNodeStates.map(_.contract.contractId) should be(
      sv1Backend.participantClient.ledger_api_extensions.acs
        .filterJava(SvNodeState.COMPANION)(dsoParty)
        .map(_.id.contractId)
    )
  }

  "returns expected splice instance names" in { implicit env =>
    val spliceInstanceNames = sv1ScanBackend.getSpliceInstanceNames()

    spliceInstanceNames.networkName should be("Splice")
    spliceInstanceNames.networkFaviconUrl should be(
      "https://www.hyperledger.org/hubfs/hyperledgerfavicon.png"
    )
    spliceInstanceNames.amuletName should be("Amulet")
    spliceInstanceNames.amuletNameAcronym should be("AMT")
    spliceInstanceNames.nameServiceName should be("Amulet Name Service")
    spliceInstanceNames.nameServiceNameAcronym should be("ANS")
  }

  "list transaction pages in ascending and descending order" in { implicit env =>
    val aliceWalletUser = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    def tapsForAlice = (t: TransactionHistoryResponseItem) =>
      t.tap.exists { tap =>
        PartyId.tryFromProtoPrimitive(tap.amuletOwner) == aliceWalletUser
      }

    val nrTaps = 10
    val amuletAmounts = (1 to nrTaps).map(walletUsdToAmulet(_))
    val pageSize = nrTaps / 2
    // filtering for Alice to avoid interference by the top up taps
    def collectAllTapPagesForAlice(sortOrder: TransactionHistoryRequest.SortOrder) = {
      LazyList
        .iterate(sv1ScanBackend.listTransactions(None, sortOrder, pageSize)) { page =>
          sv1ScanBackend.listTransactions(page.lastOption.map(_.eventId), sortOrder, pageSize)
        }
        .takeWhile(_.nonEmpty)
        .foldLeft(Seq.empty[TransactionHistoryResponseItem])(_ ++ _)
        .filter(tapsForAlice)
    }

    def toAmuletAmounts(page: Seq[TransactionHistoryResponseItem]) =
      page.flatMap(_.tap.map(t => BigDecimal(t.amuletAmount)))

    actAndCheck(
      "Tap amulets for Alice", {
        (1 to nrTaps).foreach { i =>
          aliceWalletClient.tap(BigDecimal(i))
        }
      },
    )(
      "Amulets should appear in Alice's wallet",
      _ => {
        aliceWalletClient.list().amulets should have length nrTaps.toLong
      },
    )

    eventually() {
      val latestRound =
        sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number
      val asc = TransactionHistoryRequest.SortOrder.Asc
      val desc = TransactionHistoryRequest.SortOrder.Desc
      val allPagesAsc = collectAllTapPagesForAlice(asc)
      val allPagesDesc = collectAllTapPagesForAlice(desc)
      allPagesAsc.map(_.round) should contain only Some(latestRound)

      val tapsFirstPageAscending = allPagesAsc.take(pageSize)

      toAmuletAmounts(tapsFirstPageAscending) should be(
        amuletAmounts.take(pageSize)
      )

      val firstPageEndEventId = tapsFirstPageAscending.last.eventId
      val tapsSecondPageAscending = allPagesAsc.slice(pageSize, pageSize + pageSize)
      sv1ScanBackend
        .listTransactions(
          Some(firstPageEndEventId),
          TransactionHistoryRequest.SortOrder.Asc,
          pageSize.toInt,
        )
        .filter(tapsForAlice)

      toAmuletAmounts(tapsSecondPageAscending) should be(
        amuletAmounts.slice(pageSize, pageSize + pageSize)
      )

      sv1ScanBackend
        .listTransactions(
          Some(tapsSecondPageAscending.last.eventId),
          asc,
          pageSize.toInt,
        )
        .filter(tapsForAlice) should be(empty)

      val tapsFirstPageDescending = allPagesDesc.take(pageSize)
      toAmuletAmounts(tapsFirstPageDescending) should be(
        amuletAmounts.reverse.take(pageSize)
      )

      val tapsSecondPageDescending =
        allPagesDesc.slice(pageSize, pageSize + pageSize)

      sv1ScanBackend
        .listTransactions(
          Some(tapsSecondPageDescending.last.eventId),
          TransactionHistoryRequest.SortOrder.Desc,
          pageSize.toInt,
        )
        .filter(tapsForAlice) should be(empty)

      toAmuletAmounts(tapsSecondPageDescending) should be(
        amuletAmounts.reverse.slice(pageSize, pageSize + pageSize)
      )
      toAmuletAmounts(
        tapsFirstPageAscending ++ tapsSecondPageAscending
      ) should be(toAmuletAmounts((tapsFirstPageDescending ++ tapsSecondPageDescending).reverse))
    }
  }

  "list tap and amulet merge transactions" in { implicit env =>
    val aliceUserName = aliceWalletClient.config.ledgerApiUser
    val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

    def aliceMergeAmuletsTrigger =
      aliceValidatorBackend
        .userWalletAutomation(aliceUserName)
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]

    aliceMergeAmuletsTrigger.pause().futureValue
    val tap1 = 40.0
    val tap2 = 60.0
    val aliceAmulet = tap1 + tap2
    actAndCheck(
      "Tap 2 amulets for Alice", {
        aliceWalletClient.tap(tap1)
        aliceWalletClient.tap(tap2)
      },
    )(
      "Amulets should appear in Alice's wallet",
      _ => aliceWalletClient.list().amulets should have length 2,
    )

    actAndCheck(
      "Run merge amulets automation once",
      aliceMergeAmuletsTrigger.runOnce().futureValue,
    )(
      "Verify that amulets were merged",
      workDone => {
        workDone should be(true)
        aliceWalletClient.list().amulets should have length 1
      },
    )

    eventually() {
      val taps = sv1ScanBackend.listActivity(None, 10).flatMap(_.tap).filter { tap =>
        PartyId.tryFromProtoPrimitive(
          tap.amuletOwner
        ) == aliceUserParty
      }
      taps should have size (2)
      taps.map(t =>
        (PartyId.tryFromProtoPrimitive(t.amuletOwner), BigDecimal(t.amuletAmount))
      ) shouldBe
        Vector((aliceUserParty, walletUsdToAmulet(tap2)), (aliceUserParty, walletUsdToAmulet(tap1)))

      val merges =
        sv1ScanBackend.listActivity(None, 10).flatMap(_.transfer).filter { transfer =>
          PartyId.tryFromProtoPrimitive(
            transfer.sender.party
          ) == aliceUserParty && transfer.receivers.isEmpty
        }

      merges should have size (1)
      val mergeTransfer = merges.head
      PartyId.tryFromProtoPrimitive(mergeTransfer.sender.party) shouldBe aliceUserParty
      mergeTransfer.sender.inputAmuletAmount
        .map(BigDecimal(_))
        .getOrElse(BigDecimal(0)) shouldBe walletUsdToAmulet(aliceAmulet)
      BigDecimal(mergeTransfer.sender.senderChangeAmount) shouldBe (walletUsdToAmulet(
        aliceAmulet
      ) - BigDecimal(mergeTransfer.sender.senderChangeFee) - BigDecimal(
        mergeTransfer.sender.holdingFees
      ) - BigDecimal(mergeTransfer.sender.senderFee))
    }
  }

  "list recent transfers and taps, with rewards collection and amulet merging turned off" in {
    implicit env =>
      val (aliceUserParty, bobUserParty) = onboardAliceAndBob()
      val charlieUserParty = onboardWalletUser(charlieWalletClient, aliceValidatorBackend)
      val aliceUserName = aliceWalletClient.config.ledgerApiUser
      val bobUserName = bobWalletClient.config.ledgerApiUser

      def aliceMergeAmuletsTrigger =
        aliceValidatorBackend
          .userWalletAutomation(aliceUserName)
          .futureValue
          .trigger[CollectRewardsAndMergeAmuletsTrigger]

      aliceMergeAmuletsTrigger.pause().futureValue

      def bobMergeAmuletsTrigger =
        bobValidatorBackend
          .userWalletAutomation(bobUserName)
          .futureValue
          .trigger[CollectRewardsAndMergeAmuletsTrigger]

      bobMergeAmuletsTrigger.pause().futureValue

      val latestRound =
        sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

      val amuletConfig =
        sv1ScanBackend.getAmuletConfigForRound(latestRound)

      val holdingFee = amuletConfig.holdingFee
      val bobTapAmount = 100000.0
      val aliceTapAmount = 100000.0

      actAndCheck(
        "Tap amulets for Alice and bob", {
          aliceWalletClient.tap(aliceTapAmount)
          bobWalletClient.tap(bobTapAmount)
        },
      )(
        "Amulets should appear in Alice and Bob's wallet",
        _ => {
          aliceWalletClient.list().amulets should have length 1
          bobWalletClient.list().amulets should have length 1
        },
      )
      val openRoundForTransfer =
        sv1ScanBackend.getLatestOpenMiningRound(CantonTimestamp.now()).contract.payload.round.number

      val transferAmount = BigDecimal(10000.0)
      clue("Transfer some CC to alice") {
        p2pTransfer(bobWalletClient, aliceWalletClient, aliceUserParty, transferAmount)
      }

      clue("Alice receives the transfer from bob") {
        eventually() {
          val balance = aliceWalletClient.balance()
          balance.unlockedQty shouldBe (walletUsdToAmulet(aliceTapAmount) + transferAmount)
        }
        eventually() {
          val approxBobFees = walletUsdToAmulet(3) // this value depends on transferAmount
          val balance = bobWalletClient.balance()
          assertInRange(
            balance.unlockedQty,
            (
              walletUsdToAmulet(bobTapAmount) - transferAmount - approxBobFees,
              walletUsdToAmulet(bobTapAmount) - transferAmount,
            ),
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
          val round = activities.loneElement.round
          activities.loneElement.round shouldBe Some(openRoundForTransfer)
          val transfer = activities.flatMap(_.transfer).loneElement
          val inputAmuletAmount =
            transfer.sender.inputAmuletAmount.map(BigDecimal(_)).getOrElse(BigDecimal(0))
          val senderChangeFee = BigDecimal(transfer.sender.senderChangeFee)
          senderChangeFee shouldBe (amuletConfig.amuletCreateFee)
          val senderFee = BigDecimal(transfer.sender.senderFee)
          val holdingFees = BigDecimal(transfer.sender.holdingFees)
          val senderChangeAmount = BigDecimal(transfer.sender.senderChangeAmount)

          senderFee shouldBe expectedSenderFee(transferAmount)

          val totalSenderFee = senderFee + holdingFees + senderChangeFee

          inputAmuletAmount - senderChangeAmount shouldBe (transferAmount + totalSenderFee)

          // alice receives transfer
          transfer.receivers
            .map(r => BigDecimal(r.amount))
            .sum shouldBe transferAmount
          val amuletAsOfRoundZeroAdjustment = round.value * holdingFee
          transfer.balanceChanges shouldBe Vector(
            BalanceChange(
              aliceUserParty.toProtoPrimitive,
              Codec.encode(
                transferAmount + round.value * holdingFee
              ),
              Codec.encode(
                1 * holdingFee
              ),
            ),
            BalanceChange(
              bobUserParty.toProtoPrimitive,
              Codec.encode(
                senderChangeAmount + amuletAsOfRoundZeroAdjustment - (inputAmuletAmount + holdingFee * round.value) // See AmuletRules: senderChangeAmount + amuletAsOfRoundZeroAdjustment - inp.amountArchivedAsOfRoundZero
              ),
              Codec.encode(
                BigDecimal(
                  0 // See AmuletRules: transferConfigAmulet.holdingFee.rate - amulet.amount.ratePerRound.rate
                )
              ),
            ),
          )

          // receiverFee is by default set to 0, sender pays all fees.
          transfer.receivers
            .map(r => BigDecimal(r.receiverFee)) should contain only (BigDecimal(0))

          val taps = sv1ScanBackend.listActivity(None, 10).flatMap(_.tap).filter { tap =>
            PartyId.tryFromProtoPrimitive(tap.amuletOwner) == bobUserParty
          }
          taps should have size (1)
          val tap = taps.head
          // bob tapped
          BigDecimal(tap.amuletAmount) shouldBe walletUsdToAmulet(bobTapAmount)
        }
      }
      val selfTransferAmount = BigDecimal(1000)
      clue("Self Transfer some CC from/to Bob") {
        p2pTransfer(bobWalletClient, bobWalletClient, bobUserParty, selfTransferAmount)
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
          val inputAmuletAmount =
            transfer.sender.inputAmuletAmount.map(BigDecimal(_)).getOrElse(BigDecimal(0))
          val senderChangeFee = BigDecimal(transfer.sender.senderChangeFee)
          senderChangeFee shouldBe (amuletConfig.amuletCreateFee)

          val senderFee = BigDecimal(transfer.sender.senderFee)
          val holdingFees = BigDecimal(transfer.sender.holdingFees)
          val senderChangeAmount = BigDecimal(transfer.sender.senderChangeAmount)
          senderFee shouldBe walletUsdToAmulet(SpliceUtil.defaultCreateFee.fee)

          val totalSenderFee = senderFee + holdingFees + senderChangeFee
          inputAmuletAmount - senderChangeAmount shouldBe (selfTransferAmount + totalSenderFee)

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
          aliceWalletClient.list().amulets.head,
          Seq(
            transferOutputAmulet(
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

  // TODO (DACH-NY/canton-network-node#13038) reenable
  "list collected app and validator and SV rewards" ignore { implicit env =>
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
        .filterJava(OpenMiningRound.COMPANION)(dsoParty)
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
        .futureValue
        .trigger[CollectRewardsAndMergeAmuletsTrigger]
    bobValidatorRewardsTrigger.resume()

    // The trigger that advances rounds, running in the sv app
    // Note: using `def`, as the trigger may be destroyed and recreated (when the sv delegate changes)
    def advanceTrigger = sv1Backend.dsoDelegateBasedAutomation
      .trigger[AdvanceOpenMiningRoundTrigger]

    clue("Bob grants featured app and taps, transfers to alice") {
      grantFeaturedAppRight(bobValidatorWalletClient)

      bobWalletClient.tap(walletAmuletToUsd(50))
      actAndCheck(
        "Transfer from Bob to Alice",
        p2pTransfer(bobWalletClient, aliceWalletClient, alice, 30.0),
      )(
        "Bob's validator will receive some rewards",
        _ => {
          bobValidatorWalletClient
            .listAppRewardCoupons() should have size 1
          // TODO(DACH-NY/canton-network-node#13038) Add asserts back for listValidatorRewardCoupons

          //          bobValidatorWalletClient.listValidatorRewardCoupons() should
          //            have size (if (bobToppedUp) 2 else 1)
        },
      )
    }

    val appRewardCoupons =
      bobValidatorWalletClient.listAppRewardCoupons()
    val validatorRewardCoupons =
      bobValidatorWalletClient
        .listValidatorRewardCoupons()
    clue("Advancing round") {
      eventually() {
        advanceTrigger.runOnce().futureValue should be(true)
      }
    }

    clue("Trigger automation 2 more times to advance 3 rounds") {
      (1 to 2).foreach { _ =>
        eventually() {
          advanceTrigger.runOnce().futureValue should be(true)
        }
      }
    }
    // TODO(DACH-NY/canton-network-node#13038) Add asserts back for listValidatorRewardCoupons
    // replace _ with validatorRewardAmount
    val (appRewardAmount, _) =
      getRewardCouponsValue(appRewardCoupons, validatorRewardCoupons, featured = false)

    clue("Checking app and validator reward and faucet amounts") {
      eventually() {
        bobValidatorWalletClient
          .listAppRewardCoupons() should have size 0
        // TODO(DACH-NY/canton-network-node#13038) Add asserts back for listValidatorRewardCoupons
        //        bobValidatorWalletClient
        //          .listValidatorRewardCoupons() should have size 0

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
        // TODO(DACH-NY/canton-network-node#13038) Add asserts back for listValidatorRewardCoupons

        //        val inputValidatorAmounts = bobTransfers
        //          .flatMap(_.sender.inputValidatorRewardAmount)
        //          .map(BigDecimal(_))
        //          .filter(_ != zero)
        //
        //        val inputValidatorFaucetAmounts = bobTransfers
        //          .flatMap(_.sender.inputValidatorFaucetAmount)
        //          .map(BigDecimal(_))
        //          .filter(_ != zero)
        //        if (!bobToppedUp) {
        //          val firstInputValidatorFaucetAmount = inputValidatorFaucetAmounts.head
        //          val faucetAmounts = inputValidatorFaucetAmounts.tail
        //
        //          inputValidatorAmounts should contain theSameElementsAs (Seq(
        //            validatorRewardAmount + firstInputValidatorFaucetAmount
        //          ) ++ faucetAmounts)
        //        } else {
        //          val faucetAmounts = inputValidatorFaucetAmounts
        //
        //          inputValidatorAmounts should contain theSameElementsAs (Seq(
        //            validatorRewardAmount
        //          ) ++ faucetAmounts)
        //        }
      }
    }
  }

  "getWalletBalance should return 400 for invalid party ID" in { implicit env =>
    implicit val sys = env.actorSystem
    registerHttpConnectionPoolsCleanup(env)

    val response = Http()
      .singleRequest(
        Get(
          s"${sv1ScanBackend.httpClientConfig.url}/api/scan/v0/wallet-balance?party_id=None&asOfEndOfRound=0"
        )
      )
      .futureValue

    inside(response) {
      case _ if response.status == StatusCodes.BadRequest =>
        inside(Unmarshal(response.entity).to[String].value.value) {
          case Success(successfullResponse) =>
            successfullResponse should include(
              "Invalid unique identifier `None` with missing namespace"
            )
        }
    }
  }

  "getUpdateHistory should return 400 for invalid after timestamp" in { implicit env =>
    import env.{actorSystem, executionContext}
    registerHttpConnectionPoolsCleanup(env)

    val response = Http()
      .singleRequest(
        Post(
          s"${sv1ScanBackend.httpClientConfig.url}/api/scan/v0/updates"
        ).withEntity(
          HttpEntity(
            ContentTypes.`application/json`,
            s"""{"after":{"after_migration_id":1,"after_record_time":"Invalid"},"page_size":10}""",
          )
        )
      )
      .futureValue

    inside(response) {
      case _ if response.status == StatusCodes.BadRequest =>
        inside(Unmarshal(response.entity).to[String].value.value) {
          case Success(successfullResponse) =>
            successfullResponse should include(
              "Invalid timestamp: Text 'Invalid' could not be parsed at index 0"
            )
        }
    }
  }

  "return bft sequencers" in { implicit env =>
    val bftSequencers = sv1ScanBackend.listBftSequencers()
    val sequencer = bftSequencers.loneElement
    sequencer.url should be("http://testUrl:8081")
    sequencer.migrationId should be(0)
    sequencer.id shouldBe sv1Backend.appState.localSynchronizerNode.value.sequencerAdminConnection.getSequencerId.futureValue
  }

  "respect rate limit" in { implicit env =>
    import env.{actorSystem, executionContext}

    val results = SpliceRateLimiterTest
      .runRateLimited(
        6,
        30,
      ) {
        Future {
          blocking {
            sv1ScanBackend.getAcsSnapshot(
              dsoParty,
              None,
            )
          }
        }
      } futureValue

    results.count(identity) should be(25 +- 1)

  }

  def expectedSenderFee(amount: BigDecimal) = {
    val initialRate = SpliceUtil.defaultTransferFee.initialRate
    val step1 = SpliceUtil.defaultTransferFee.steps.get(0)
    val step2 = SpliceUtil.defaultTransferFee.steps.get(1)
    val step3 = SpliceUtil.defaultTransferFee.steps.get(2)
    val (step1Amount, step1Mult) = (walletUsdToAmulet(step1._1), step1._2)
    val (step2Amount, step2Mult) = (walletUsdToAmulet(step2._1), step2._2)
    // ensuring the right steps are hardcoded here.
    walletUsdToAmulet(step3._1) should be > amount
    val steppedRate =
      initialRate * (amount min step1Amount) +
        step1Mult * ((amount - step1Amount) max 0 min step2Amount) +
        step2Mult * ((amount - step1Amount - step2Amount) max 0)
    walletUsdToAmulet(SpliceUtil.defaultCreateFee.fee) + steppedRate
  }

  def triggerTopupAliceAndBob()(implicit env: SpliceTestConsoleEnvironment): (Boolean, Boolean) = {
    val aliceTopupTrigger =
      aliceValidatorBackend.appState.automation.trigger[TopupMemberTrafficTrigger]
    val bobTopupTrigger =
      bobValidatorBackend.appState.automation.trigger[TopupMemberTrafficTrigger]
    bobTopupTrigger.pause().futureValue
    aliceTopupTrigger.pause().futureValue
    (aliceTopupTrigger.runOnce().futureValue, bobTopupTrigger.runOnce().futureValue)
  }
}
