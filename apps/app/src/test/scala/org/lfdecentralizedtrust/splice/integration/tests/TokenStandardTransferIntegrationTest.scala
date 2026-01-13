package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.console.CommandFailure
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAllScanAppConfigs_,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransactionHistoryResponseItem.TransactionType as HttpTransactionType
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AbortTransferInstruction,
  ReceiverAmount,
  Transfer,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.store.ChoiceContextContractFetcher
import org.lfdecentralizedtrust.splice.util.{DisclosedContracts, WalletTestUtil}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
}

import java.util.UUID
import scala.jdk.CollectionConverters.*

// this test sets fees to zero, and that only works from 0.1.14 onwards
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_14
class TokenStandardTransferIntegrationTest
    extends IntegrationTestWithSharedEnvironment
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
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
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
              _.listTokenStandardTransfers() should have size i.toLong
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
              _.listTokenStandardTransfers() should have size (cids.length.toLong - 1L)
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
              _.listTokenStandardTransfers() should have size (cids.length.toLong - 2L)
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
              _.listTokenStandardTransfers() should have size (cids.length.toLong - 3L)
            )
            bobWalletClient.balance().unlockedQty should be > BigDecimal(0)
          },
        )
      }

      val (aliceTxs, bobTxs) = clue("Alice and Bob parse all tx log entries") {
        eventually() {
          val aliceTxs = aliceWalletClient.listTransactions(None, 1000)
          // tap + 4 transfer instructions + accept + withdraw + reject
          aliceTxs should have size 8
          val bobTxs = bobWalletClient.listTransactions(None, 1000)
          // 4 transfer instructions + accept + withdraw + reject
          bobTxs should have size 7
          (aliceTxs, bobTxs)
        }
      }

      // Thanks for zero fees we can check the exact balances w/o complex fee calculations.
      clue("Check the exact balances of alice ") {
        val balances = aliceWalletClient.balance()
        balances.unlockedQty shouldBe BigDecimal(19980.0)
        balances.lockedQty shouldBe BigDecimal(10.0)
      }

      clue("Check the exact balances of bob ") {
        val balances = bobWalletClient.balance()
        balances.unlockedQty shouldBe BigDecimal(10.0)
        balances.lockedQty shouldBe BigDecimal(0.0)
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
            logEntry.receivers shouldBe empty
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
            logEntry.receivers shouldBe empty
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
            logEntry.receivers shouldBe empty
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
            logEntry.receivers shouldBe empty
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

      // TODO(#2254): check the exact balances once the scan backend supports it

      val activityTxs = eventually() {
        val activityTxs = sv1ScanBackend
          .listActivity(None, 1000)
          .filter(t => t.transfer.isDefined || t.abortTransferInstruction.isDefined)
        // 4 transfer instructions + accept + withdraw + reject
        activityTxs should have size (7)
        activityTxs
      }

      clue("TransferInstruction accept") {
        activityTxs(0).transactionType shouldBe HttpTransactionType.Transfer
        val transfer = activityTxs(0).transfer.value
        transfer.sender.party shouldBe aliceUserParty.toProtoPrimitive
        transfer.transferInstructionCid shouldBe Some(cids(2).contractId)
        transfer.description shouldBe None
        transfer.transferInstructionReceiver shouldBe None
        transfer.transferKind shouldBe Some(Transfer.TransferKind.members.TransferInstructionAccept)
        transfer.receivers shouldBe Seq(
          ReceiverAmount(bobUserParty.toProtoPrimitive, "10.0000000000", "0.0000000000")
        )
      }
      clue("TransferInstruction withdraw") {
        activityTxs(1).transactionType shouldBe HttpTransactionType.AbortTransferInstruction
        val abort = activityTxs(1).abortTransferInstruction.value
        abort.transferInstructionCid shouldBe cids(1).contractId
        abort.abortKind shouldBe AbortTransferInstruction.AbortKind.members.Withdraw
        // Scan tracks the sum of locked and unlocked amulets so there is no balance change here.
      }
      clue("TransferInstruction reject") {
        activityTxs(2).transactionType shouldBe HttpTransactionType.AbortTransferInstruction
        val abort = activityTxs(2).abortTransferInstruction.value
        abort.transferInstructionCid shouldBe cids(0).contractId
        abort.abortKind shouldBe AbortTransferInstruction.AbortKind.members.Reject
        // Scan tracks the sum of locked and unlocked amulets so there is no balance change here.
      }
      forAll(Seq(3, 4, 5, 6)) { i =>
        val transferInstructionNumber = 7 - i
        clue(s"Transfer #$transferInstructionNumber") {
          activityTxs(i).transactionType shouldBe HttpTransactionType.Transfer
          val transfer = activityTxs(i).transfer.value
          transfer.sender.party shouldBe aliceUserParty.toProtoPrimitive
          transfer.transferInstructionCid shouldBe Some(
            cids(transferInstructionNumber - 1).contractId
          )
          transfer.description shouldBe Some(
            s"Transfer #$transferInstructionNumber"
          )
          transfer.transferInstructionReceiver shouldBe Some(bobUserParty.toProtoPrimitive)
          transfer.transferKind shouldBe Some(
            Transfer.TransferKind.members.CreateTransferInstruction
          )
          transfer.sender.party shouldBe aliceUserParty.toProtoPrimitive
          val receiver = transfer.receivers.loneElement
          receiver.party shouldBe aliceUserParty.toProtoPrimitive
          BigDecimal(receiver.amount) shouldBe (BigDecimal(10))
        }
      }
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
      val openRound = sv1ScanBackend.getLatestOpenMiningRound(env.environment.clock.now)
      // We don't have a locked expiry trigger atm so trigger expiry manually.
      actAndCheck(
        "Alice expires locked amulet",
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(aliceParty),
            commands = locked.contract.contractId
              .exerciseLockedAmulet_OwnerExpireLock(
                openRound.contractId
              )
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = DisclosedContracts
              .forTesting(
                openRound
              )
              .toLedgerApiDisclosedContracts,
          ),
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
          aliceTxs should have size 4
          val bobTxs = bobWalletClient.listTransactions(None, 1000)
          // transfer instruction + withdraw
          bobTxs should have size 2
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
            logEntry.receivers shouldBe empty
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

      assertThrows[CommandFailure](
        loggerFactory.assertLogs(
          aliceWalletClient.createTokenStandardTransfer(
            bobUserParty,
            10,
            "not ok, resubmitted same trackingId so should be rejected",
            expiration,
            trackingId,
          ),
          _.errorMessage should include("Command submission already exists"),
        )
      )

      eventually() {
        inside(aliceWalletClient.listTokenStandardTransfers()) { case Seq(t) =>
          t.contractId.contractId should be(created.output match {
            case members.TransferInstructionPending(value) => value.transferInstructionCid
            case x => fail(s"Expected pending transfer, got $x")
          })
        }
      }
    }

  }

}
