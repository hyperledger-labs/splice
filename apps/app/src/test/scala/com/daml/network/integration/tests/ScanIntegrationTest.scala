package com.daml.network.integration.tests

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

class ScanIntegrationTest
    extends CNNodeIntegrationTest
    with ConfigScheduleUtil
    with WalletTestUtil
    with TimeTestUtil {

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

  "list tap and coin merge transactions in recent activity" in { implicit env =>
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
}
