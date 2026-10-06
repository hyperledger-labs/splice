package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv2.transferinstructionresult_output.TransferInstructionResult_Completed
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  holdingv2,
  metadatav1,
  transferinstructionv2,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms.{
  ConfigurableApp,
  updateAllScanAppConfigs_,
  updateAutomationConfig,
}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTest
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardV2TestUtil.ExpectedTrafficCost
import org.lfdecentralizedtrust.splice.scan.automation.{
  AcsSnapshotBackfillingTrigger,
  AcsSnapshotTrigger,
}
import org.lfdecentralizedtrust.splice.store.ChoiceContextContractFetcher
import org.lfdecentralizedtrust.splice.util.{
  SynchronizerFeesTestUtil,
  TokenStandardMetadata,
  WalletTestUtil,
}
import org.lfdecentralizedtrust.splice.wallet.automation.CollectRewardsAndMergeAmuletsTrigger
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction
import com.digitalasset.canton.topology.PartyId

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

// this test sets fees to zero, and that only works from 0.1.14 onwards
@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_14
class TokenStandardV2TransferIntegrationTest
    extends IntegrationTest
    with WalletTestUtil
    with WalletTxLogTestUtil
    with HasActorSystem
    with HasExecutionContext
    with TokenStandardTest
    with TokenStandardV2TestUtil
    with SynchronizerFeesTestUtil {

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
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
      aliceWalletClient.tap(100)

      val responses = (1 to 4).map { i =>
        actAndCheck(
          "Alice creates transfer offer",
          aliceWalletClient.createTokenStandardTransferV2(
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
            new transferinstructionv2.TransferInstruction.ContractId(value.transferInstructionCid)
          case _ => fail("The transfers were expected to be pending.")
        }
      }

      clue("Scan sees all the transfers") {
        cids.foreach { cid =>
          eventuallySucceeds() {
            sv1ScanBackend.getTransferInstructionAcceptContextV2(cid)
            sv1ScanBackend.getTransferInstructionRejectContextV2(cid)
            sv1ScanBackend.getTransferInstructionWithdrawContextV2(cid)
          }
        }
      }

      inside(cids.toList) { case toReject :: toWithdraw :: toAccept :: _toIgnore :: Nil =>
        actAndCheck(
          "Bob rejects one transfer offer",
          bobWalletClient.rejectTokenStandardTransferV2(toReject),
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
          aliceWalletClient.withdrawTokenStandardTransferV2(toWithdraw),
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
          bobWalletClient.acceptTokenStandardTransferV2(toAccept),
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

      // Thanks to zero fees we can check the exact balances w/o complex fee calculations.
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
        Seq[CheckTxHistoryFn](
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
        ) ++ (1 to 4).zip(cids).reverse.map[CheckTxHistoryFn] {
          case (n, cid) => { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
            logEntry.transferInstructionCid shouldBe cid.contractId
            logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
            logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
            logEntry.description shouldBe s"Transfer #$n"
            // No balance is transferred here so receivers is empty
            logEntry.receivers shouldBe empty withClue "receivers"
            // The wallet counts moving the balance to a locked amulet as a negative balance change
            logEntry.sender.value.amount shouldBe (BigDecimal(-10))
          }
        } ++ Seq[CheckTxHistoryFn](
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          }
        ),
      )

      checkTxHistory(
        bobWalletClient,
        Seq[CheckTxHistoryFn](
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
        ) ++
          (1 to 4).zip(cids).reverse.map[CheckTxHistoryFn] {
            case (n, cid) => { case logEntry: TransferTxLogEntry =>
              logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.CreateTokenStandardTransferInstruction.toProto
              logEntry.transferInstructionCid shouldBe cid.contractId
              logEntry.transferInstructionReceiver shouldBe bobUserParty.toProtoPrimitive
              logEntry.transferInstructionAmount shouldBe Some(BigDecimal(10))
              logEntry.description shouldBe s"Transfer #$n"
              logEntry.receivers shouldBe Seq(PartyAndAmount(bobUserParty.toProtoPrimitive, 0))
              logEntry.sender.value.amount shouldBe 0
            }
          },
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

      val offerResponse = aliceWalletClient.createTokenStandardTransferV2(
        bobParty,
        10,
        "transfer offer description",
        expiration,
        trackingId,
      )
      val offer = inside(offerResponse.output) { case members.TransferInstructionPending(value) =>
        new transferinstructionv2.TransferInstruction.ContractId(value.transferInstructionCid)
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
          val context = sv1ScanBackend.getTransferInstructionWithdrawContextV2(offer)
          context.choiceContext.values.get("expire-lock") shouldBe new metadatav1.anyvalue.AV_Bool(
            false
          )
        },
      )
      aliceWalletClient.withdrawTokenStandardTransferV2(offer)

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

      val created = aliceWalletClient.createTokenStandardTransferV2(
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
      val resubmitted = aliceWalletClient.createTokenStandardTransferV2(
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

    "support single step transfers" in { implicit env =>
      // they need to be hosted on the same participant for this test
      // in practice, we expect this choice to be called as part of a larger
      // Daml tx, e.g., to distribute holdings that were just received by an
      // decentralized app to all its backers.
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val aliceValidatorParty = aliceValidatorBackend.getValidatorPartyId()
      aliceWalletClient.tap(100)
      val aliceBalanceBefore = aliceWalletClient.balance().unlockedQty

      val aliceAmuletHoldings = aliceWalletClient
        .list()
        .amulets
        .map(_.contract.contractId.toInterface(holdingv2.Holding.INTERFACE))

      val (context, _) =
        sv1ScanBackend.getTransferFactoryV2(
          new transferinstructionv2.TransferFactory_Transfer(
            new transferinstructionv2.Transfer(
              basicAccount(aliceUserParty),
              basicAccount(aliceValidatorParty),
              BigDecimal(10).bigDecimal,
              new holdingv2.InstrumentId(dsoParty.toProtoPrimitive, amuletInstrumentIdName),
              Instant.now(),
              Instant.now().plusSeconds(3600L),
              aliceAmuletHoldings.asJava,
              emptyMetadata,
            ),
            /* actors */ Seq(aliceUserParty, aliceValidatorParty).map(_.toProtoPrimitive).asJava,
            emptyExtraArgs,
          )
        )

      actAndCheck(
        "Alice executes the single-step transfer", {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitWithResult(
              userId = aliceWalletClient.config.ledgerApiUser,
              actAs = Seq(aliceUserParty, aliceValidatorParty),
              readAs = Seq(aliceUserParty, aliceValidatorParty),
              update = context.factoryId
                .exerciseTransferFactory_Transfer(context.args),
              disclosedContracts = context.disclosedContracts,
            )
        },
      )(
        "The transfer is completed",
        result => {
          result.exerciseResult.output match {
            case _: TransferInstructionResult_Completed =>
            case other =>
              fail(s"Expected transfer to complete, but got: $other")
          }
          aliceWalletClient.balance().unlockedQty should be(aliceBalanceBefore - 10)
          aliceValidatorWalletClient.balance().unlockedQty should be(BigDecimal(10))
        },
      )
    }

    "support preapproved transfers across participants" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)

      createTransferPreapprovalEnsuringItExists(bobWalletClient, bobValidatorBackend)

      aliceWalletClient.tap(100)
      val aliceBalanceBefore = aliceWalletClient.balance().unlockedQty
      val bobBalanceBefore = bobWalletClient.balance().unlockedQty

      val aliceAmuletHoldings = aliceWalletClient
        .list()
        .amulets
        .map(_.contract.contractId.toInterface(holdingv2.Holding.INTERFACE))
      aliceAmuletHoldings should have size 1

      val (context, kind) =
        sv1ScanBackend.getTransferFactoryV2(
          new transferinstructionv2.TransferFactory_Transfer(
            new transferinstructionv2.Transfer(
              basicAccount(aliceUserParty),
              basicAccount(bobUserParty),
              BigDecimal(10).bigDecimal,
              new holdingv2.InstrumentId(dsoParty.toProtoPrimitive, amuletInstrumentIdName),
              Instant.now(),
              Instant.now().plusSeconds(3600L),
              aliceAmuletHoldings.asJava,
              emptyMetadata,
            ),
            /* actors */ Seq(aliceUserParty).map(_.toProtoPrimitive).asJava,
            emptyExtraArgs,
          )
        )
      kind shouldBe transferinstruction.v2.definitions.TransferFactoryWithChoiceContext.TransferKind.Direct

      val transferUpdate = context.factoryId.exerciseTransferFactory_Transfer(context.args)
      val (transferTx, _) = actAndCheck(
        "Alice transfers to Bob's preapproval", {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              userId = aliceWalletClient.config.ledgerApiUser,
              actAs = Seq(aliceUserParty),
              readAs = Seq(aliceUserParty),
              commands = transferUpdate.commands.asScala.toSeq,
              disclosedContracts = context.disclosedContracts,
            )
        },
      )(
        "The balances are updated",
        _ => {
          aliceWalletClient.balance().unlockedQty should be(aliceBalanceBefore - 10)
          bobWalletClient.balance().unlockedQty should be(bobBalanceBefore + 10)
        },
      )

      inside(
        SpliceLedgerConnection
          .decodeExerciseResult(transferUpdate, transferTx)
          .exerciseResult
          .output
      ) { case _: TransferInstructionResult_Completed => () }

      // The reference traffic cost was recorded by running this test on PV=36
      // On PV=35, the traffic costs are:
      // 5,133 bytes in integration test, and
      // 8,552 bytes on DevNet (14 SVs, Splice 0.8.0, 167% of the reference cost)
      // tx: https://lighthouse.devnet.cantonloop.com/transactions/1220e05b82ba62455190c29a845272faabb45eeb02253e33a07e40079ac5ebfe4f6c
      checkTrafficCosts(
        Seq(transferTx.getUpdateId -> ExpectedTrafficCost("Preapproved CC transfer", 5152))
      )
    }

    "buy traffic through a transfer to the traffic purchase receiver" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(100)

      val memberId = aliceValidatorBackend.participantClient.id
      val synchronizerId = activeSynchronizerId
      val minimumTrafficAmount = Math.max(
        sv1ScanBackend
          .getAmuletConfigAsOf(env.environment.clock.now)
          .decentralizedSynchronizer
          .fees
          .minTopupAmount
          .toLong,
        1_000_000L,
      )
      val (_, minimumCostAmulet) = computeSynchronizerFees(minimumTrafficAmount)
      val spentAmuletAmount = minimumCostAmulet * 2
      val purchasedTrafficAmount = computeTrafficForAmuletAmount(spentAmuletAmount)
      val memo =
        s"cip-0128/memo:memberId=${memberId.toProtoPrimitive}" +
          s"&synchronizerId=${synchronizerId.toProtoPrimitive}" +
          s"&migrationId=${sv1ScanBackend.getMigrationId()}"
      val purchasedTrafficBefore = getTotalPurchasedTraffic(memberId, synchronizerId)
      val balanceBefore = aliceWalletClient.balance().unlockedQty

      val (result, _) = actAndCheck(
        "Alice buys traffic via a V2 transfer to the traffic purchase receiver",
        aliceWalletClient.createTokenStandardTransferV2(
          PartyId.tryFromProtoPrimitive(TokenStandardMetadata.trafficPurchaseReceiver),
          spentAmuletAmount,
          memo,
          CantonTimestamp.now().plusSeconds(3600L),
          UUID.randomUUID().toString,
        ),
      )(
        "The DSO sees the purchased traffic",
        _ =>
          getTotalPurchasedTraffic(
            memberId,
            synchronizerId,
          ) shouldBe purchasedTrafficBefore + purchasedTrafficAmount,
      )
      inside(result.output) { case members.TransferInstructionCompleted(value) =>
        value.receiverHoldingCids shouldBe empty
      }
      balanceBefore - aliceWalletClient.balance().unlockedQty shouldBe spentAmuletAmount

      checkTxHistory(
        aliceWalletClient,
        Seq(
          { case logEntry: TransferTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.ExtraTrafficPurchase.toProto
            logEntry.description shouldBe memo
            logEntry.receivers shouldBe empty
            logEntry.sender.value.amount shouldBe -spentAmuletAmount
          },
          { case logEntry: BalanceChangeTxLogEntry =>
            logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
          },
        ),
      )
    }
  }

}
