package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAllScanAppConfigs_,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.scan.automation.{
  AcsSnapshotBackfillingTrigger,
  AcsSnapshotTrigger,
}
import org.lfdecentralizedtrust.splice.store.ChoiceContextContractFetcher
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
}

import java.util.UUID

// this test sets fees to zero, and that only works from 0.1.14 onwards
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_14
class TokenStandardTransferIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with WalletTxLogTestUtil
    with HasActorSystem
    with HasExecutionContext {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      // Disable automerging to make the tx history deterministic
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(ConfigurableApp.Validator)(
          _.withPausedTrigger[CollectRewardsAndMergeAmuletsTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAutomationConfig(
          ConfigurableApp.Scan
        )( // we force snapshots (via getTotalAmuletBalance) half-way through the test
          _.withPausedTrigger[AcsSnapshotTrigger].withPausedTrigger[AcsSnapshotBackfillingTrigger]
        )(config)
      )
      .addConfigTransforms((_, config) =>
        updateAllScanAppConfigs_(scanConfig =>
          scanConfig.copy(parameters =
            scanConfig.parameters.copy(contractFetchLedgerFallbackConfig =
              ChoiceContextContractFetcher.StoreContractFetcherWithLedgerFallbackConfig(
                enabled = false // expiry test doesn't see the archival otherwise
              )
            )
          )
        )(config)
      )
  }

  "Token Standard Transfers should" should {

    "support create, list, accept, reject and withdraw" in { implicit env =>
      val _ = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100)

      val responses = (1 to 4).map { i =>
        actAndCheck(
          "Alice creates transfer offer",
          aliceWalletClient.createTokenStandardTransfer(
            bobUserParty,
            10,
            s"Transfer #$i",
            CantonTimestamp.now().plusSeconds(3600L),
            UUID.randomUUID().toString,
          ),
        )(
          "Alice and Bob see it",
          _ => {
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers() should have size i.toLong withClue "TokenStandardTransfers"
            )
          },
        )._1
      }

      val cids = responses.map { response =>
        response.output match {
          case members.TransferInstructionPending(value) =>
            new TransferInstruction.ContractId(value.transferInstructionCid)
          case _ => fail("The transfers were expected to be pending.")
        }
      }

      clue("Scan sees all the transfers") {
        cids.foreach { cid =>
          eventuallySucceeds() {
            sv1ScanBackend.getTransferInstructionRejectContext(cid)
          }
        }
      }

      inside(cids.toList) { case toReject :: toWithdraw :: toAccept :: _toIgnore :: Nil =>
        actAndCheck(
          "Bob rejects one transfer offer",
          bobWalletClient.rejectTokenStandardTransfer(toReject),
        )(
          "The offer is removed, no change to Bob's balance",
          result => {
            inside(result.output) { case members.TransferInstructionFailed(_) => () }
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers() should have size (cids.length.toLong - 1L) withClue "TokenStandardTransfers"
            )
            bobWalletClient.balance().unlockedQty should be(BigDecimal(0))
          },
        )

        actAndCheck(
          "Alice withdraws one transfer offer",
          aliceWalletClient.withdrawTokenStandardTransfer(toWithdraw),
        )(
          "The offer is removed, no change to Bob's balance",
          result => {
            inside(result.output) { case members.TransferInstructionFailed(_) => () }
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers() should have size (cids.length.toLong - 2L) withClue "TokenStandardTransfers"
            )
            bobWalletClient.balance().unlockedQty should be(BigDecimal(0))
          },
        )

        actAndCheck(
          "Bob accepts one transfer offer",
          bobWalletClient.acceptTokenStandardTransfer(toAccept),
        )(
          "The offer is removed and bob's balance is updated",
          result => {
            inside(result.output) { case members.TransferInstructionCompleted(_) => () }
            Seq(aliceWalletClient, bobWalletClient).foreach(
              _.listTokenStandardTransfers() should have size (cids.length.toLong - 3L) withClue "TokenStandardTransfers"
            )
            bobWalletClient.balance().unlockedQty should be > BigDecimal(0)
          },
        )
      }

      val (aliceTxs, bobTxs) = clue("Alice and Bob parse all tx log entries") {
        eventually() {
          val aliceTxs = aliceWalletClient.listTransactions(None, 1000)
          // tap + 4 transfer instructions + accept + withdraw + reject
          aliceTxs should have size 8 withClue "aliceTxs"
          val bobTxs = bobWalletClient.listTransactions(None, 1000)
          // 4 transfer instructions + accept + withdraw + reject
          bobTxs should have size 7 withClue "bobTxs"
          (aliceTxs, bobTxs)
        }
      }

      // Thanks for zero fees we can check the exact balances w/o complex fee calculations.
      val aliceExpectedUnlocked = BigDecimal(19980.0)
      val aliceExpectedLocked = BigDecimal(10.0)
      clue("Check the exact balances of alice ") {
        val balances = aliceWalletClient.balance()
        balances.unlockedQty shouldBe aliceExpectedUnlocked
        balances.lockedQty shouldBe aliceExpectedLocked
      }

      val bobExpectedUnlocked = BigDecimal(10.0)
      val bobExpectedLocked = BigDecimal(0.0)
      clue("Check the exact balances of bob ") {
        val balances = bobWalletClient.balance()
        balances.unlockedQty shouldBe bobExpectedUnlocked
        balances.lockedQty shouldBe bobExpectedLocked
      }

      checkTxHistory(
        aliceWalletClient,
        Seq(
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.TransferInstruction_Accept.toProto
            logEntry.transferInstructionCid shouldBe cids(2).contractId
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe Seq(
              PartyAndAmount(bobUserParty.toProtoPrimitive, BigDecimal(10.0))
            )
            logEntry.sender.value.amount shouldBe (BigDecimal(0.0))
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.TransferInstruction_Withdraw.toProto
            logEntry.transferInstructionCid shouldBe cids(1).contractId
            logEntry.amount shouldBe BigDecimal(10.0)
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.TransferInstruction_Reject.toProto
            logEntry.transferInstructionCid shouldBe cids(0).contractId
            logEntry.amount shouldBe BigDecimal(10.0)
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(3).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #4"
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe empty withClue "receivers"
            // The wallet counts moving the balance to a locked amulet as a negative balance change
            logEntry.sender.value.amount shouldBe (BigDecimal(-10))
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(2).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #3"
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe empty withClue "receivers"
            // The wallet counts moving the balance to a locked amulet as a negative balance change
            logEntry.sender.value.amount shouldBe (BigDecimal(-10))
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(1).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #2"
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe empty withClue "receivers"
            // The wallet counts moving the balance to a locked amulet as a negative balance change
            logEntry.sender.value.amount shouldBe (BigDecimal(-10))
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(0).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #1"
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe empty withClue "receivers"
            // The wallet counts moving the balance to a locked amulet as a negative balance change
            logEntry.sender.value.amount shouldBe (BigDecimal(-10))
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          },
        ),
      )

      checkTxHistory(
        bobWalletClient,
        Seq(
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.TransferInstruction_Accept.toProto
            logEntry.transferInstructionCid shouldBe cids(2).contractId
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe Seq(
              PartyAndAmount(bobUserParty.toProtoPrimitive, BigDecimal(10.0))
            )
            logEntry.sender.value.amount shouldBe (BigDecimal(0.0))
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.TransferInstruction_Withdraw.toProto
            logEntry.transferInstructionCid shouldBe cids(1).contractId
            logEntry.amount shouldBe 0
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.TransferInstruction_Reject.toProto
            logEntry.transferInstructionCid shouldBe cids(0).contractId
            logEntry.amount shouldBe 0
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(3).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #4"
            logEntry.receivers shouldBe Seq(PartyAndAmount(bobUserParty.toProtoPrimitive, 0))
            logEntry.sender.value.amount shouldBe 0
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(2).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #3"
            logEntry.receivers shouldBe Seq(PartyAndAmount(bobUserParty.toProtoPrimitive, 0))
            logEntry.sender.value.amount shouldBe 0
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(1).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #2"
            logEntry.receivers shouldBe Seq(PartyAndAmount(bobUserParty.toProtoPrimitive, 0))
            logEntry.sender.value.amount shouldBe 0
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cids(0).contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "Transfer #1"
            logEntry.receivers shouldBe Seq(PartyAndAmount(bobUserParty.toProtoPrimitive, 0))
            logEntry.sender.value.amount shouldBe 0
          },
        ),
      )

      sv1ScanBackend
        .getTotalAmuletBalance() shouldBe (bobExpectedLocked + bobExpectedUnlocked + aliceExpectedLocked + aliceExpectedUnlocked)

    }

    "locked amulet is expired before withdraw" in { implicit env =>
      val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100.0)

      val expiration = CantonTimestamp.now().plusSeconds(5L)

      val trackingId = UUID.randomUUID().toString

      val offerResponse = aliceWalletClient.createTokenStandardTransfer(
        bobParty,
        10,
        "transfer offer description",
        expiration,
        trackingId,
      )
      val offer = inside(offerResponse.output) { case members.TransferInstructionPending(value) =>
        new TransferInstruction.ContractId(value.transferInstructionCid)
      }
      val locked = aliceWalletClient.list().lockedAmulets.loneElement
      // Wait until expiry is reached. A time-based test would be a bit cleaner but
      // environment setup is slower than sleeping for 5s so we do it here anyway.
      Threading.sleep(5000)
      // We don't have a locked expiry trigger atm so trigger expiry manually.
      actAndCheck(
        "Alice expires locked amulet",
        ownerExpireLock(aliceParty, locked.contract.contractId),
      )(
        "Scan ingests update",
        _ => {
          val context = sv1ScanBackend.getTransferInstructionWithdrawContext(offer)
          context.choiceContext.values.get("expire-lock") shouldBe new metadatav1.anyvalue.AV_Bool(
            false
          )
        },
      )
      aliceWalletClient.withdrawTokenStandardTransfer(offer)

      val (aliceTxs, bobTxs) = clue("Alice and Bob parse all tx log entries") {
        eventually() {
          val aliceTxs = aliceWalletClient.listTransactions(None, 1000)
          // tap + transfer instruction + unlock + withdraw
          aliceTxs should have size 4 withClue "aliceTxs"
          val bobTxs = bobWalletClient.listTransactions(None, 1000)
          // transfer instruction + withdraw
          bobTxs should have size 2 withClue "bobTxs"
          (aliceTxs, bobTxs)
        }
      }

      checkTxHistory(
        aliceWalletClient,
        Seq(
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.TransferInstruction_Withdraw.toProto
            logEntry.transferInstructionCid shouldBe offer.contractId
            logEntry.amount shouldBe 0
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.LockedAmuletOwnerExpired.toProto
            logEntry.amount shouldBe (10)
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe offer.contractId
            logEntry.transferInstructionReceiver shouldBe bobParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "transfer offer description"
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe empty withClue "receivers"
            // The wallet counts moving the balance to a locked amulet as a negative balance change
            logEntry.sender.value.amount shouldBe (BigDecimal(-10))
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          },
        ),
      )

      checkTxHistory(
        bobWalletClient,
        Seq(
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.TransferInstruction_Withdraw.toProto
            logEntry.transferInstructionCid shouldBe offer.contractId
            logEntry.amount shouldBe 0
          },
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe offer.contractId
            logEntry.transferInstructionReceiver shouldBe bobParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe "transfer offer description"
            logEntry.receivers shouldBe Seq(PartyAndAmount(bobParty.toProtoPrimitive, 0))
            logEntry.sender.value.amount shouldBe 0
          },
        ),
      )
    }

    "prevent duplicate transfer creation" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100.0)

      val expiration = CantonTimestamp.now().plusSeconds(3600L)

      val trackingId = UUID.randomUUID().toString

      val created = aliceWalletClient.createTokenStandardTransfer(
        bobUserParty,
        10,
        "ok",
        expiration,
        trackingId,
      )

      val createdCid = created.output match {
        case members.TransferInstructionPending(value) => value.transferInstructionCid
        case x => fail(s"Expected pending transfer, got $x")
      }

      // Resubmitting the same trackingId is deduplicated idempotently: the accepted duplicate is
      // recovered centrally and returns the original result instead of failing.
      val resubmitted = aliceWalletClient.createTokenStandardTransfer(
        bobUserParty,
        10,
        "resubmitted with the same trackingId",
        expiration,
        trackingId,
      )
      inside(resubmitted.output) { case members.TransferInstructionPending(value) =>
        value.transferInstructionCid shouldBe createdCid
      }

      // Still exactly one transfer instruction, i.e. no duplicate was created.
      eventually() {
        inside(aliceWalletClient.listTokenStandardTransfers()) { case Seq(t) =>
          t.contractId.contractId should be(createdCid)
        }
      }
    }

  }

}
