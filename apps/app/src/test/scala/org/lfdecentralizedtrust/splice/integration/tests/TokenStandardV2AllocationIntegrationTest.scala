package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocationv2 as amuletallocationV2Codegen
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv2,
  allocationv2,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingappv2
import org.lfdecentralizedtrust.splice.console.WalletAppClientReference
import org.lfdecentralizedtrust.splice.http.v0.definitions.AllocationInstructionResultOutput.members.AllocationInstructionResultCompleted
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithIsolatedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardV2AllocationIntegrationTest.{
  AllocatedOtcTrade,
  CreateAllocationRequestResult,
}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
}
import org.lfdecentralizedtrust.splice.codegen.java.da

import scala.jdk.CollectionConverters.*
import scala.util.Random

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class TokenStandardV2AllocationIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with TokenStandardV2TestUtil
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        Seq(
          sv1ValidatorBackend,
          aliceValidatorBackend,
          bobValidatorBackend,
          splitwellValidatorBackend,
        ).foreach { backend =>
          backend.participantClient.upload_dar_unless_exists(tokenStandardV2TestDarPath)
        }
      })
  }

  val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
  val emptyChoiceContext = new metadatav1.ChoiceContext(java.util.Map.of())
  val emptyExtraArgs = new metadatav1.ExtraArgs(
    emptyChoiceContext,
    emptyMetadata,
  )
  // although holding fees are not applied anymore, they are still in the checkBalance assertion
  // TODO (#4094): remove this
  val holdingFeesBound = (BigDecimal(0.0), BigDecimal(1.0))
  val tapAmount = walletUsdToAmulet(1000.0)
  val aliceTransferAmount = walletUsdToAmulet(100.0)
  val bobTransferAmount = walletUsdToAmulet(20.0)

  "Settle a DvP using allocations" in { implicit env =>
    val AllocatedOtcTrade(
      venueParty,
      aliceParty,
      bobParty,
      aliceAllocationRequestId,
      aliceAllocationId,
      bobAllocationRequestId,
      bobAllocationId,
      otcTrade,
    ) = setupAllocatedOtcTrade()
    val allocations = Seq(aliceAllocationId, bobAllocationId)
    // equivalent to mkOtcTradeSettlementInfo in Daml
    val settlementInfo = new allocationv2.SettlementInfo(
      java.util.List.of(venueParty.toProtoPrimitive),
      new allocationv2.Reference(
        "OTCTrade",
        java.util.Optional.of(new metadatav1.AnyContract.ContractId(otcTrade.id.contractId)),
      ),
      otcTrade.data.createdAt,
      otcTrade.data.settleAt,
      otcTrade.data.settlementDeadline,
      emptyMetadata,
    )
    val settleBatch = new allocationv2.SettlementFactory_SettleBatch(
      settlementInfo,
      otcTrade.data.transferLegs,
      allocations.asJava,
      /*actors = */ java.util.List.of(venueParty.toProtoPrimitive),
      emptyExtraArgs,
    )
    val settlementFactoryWithDisclosures =
      sv1ScanBackend.getSettlementFactoryV2(
        settleBatch
      )

    val otcTradeSettleArgs = new tradingappv2.OTCTrade_Settle(
      Map[String, tradingappv2.SettlementBatch](
        dsoParty.toProtoPrimitive -> new tradingappv2.settlementbatch.SettlementBatchV2(
          allocations.asJava,
          /*missingAllocations=*/ java.util.List.of(),
          settlementFactoryWithDisclosures.factoryId,
          settlementFactoryWithDisclosures.args.extraArgs,
        )
      ).asJava,
      List(
        new tradingappv2.OTCTradeAllocationRequest.ContractId(
          aliceAllocationRequestId.contractId
        ),
        new tradingappv2.OTCTradeAllocationRequest.ContractId(
          bobAllocationRequestId.contractId
        ),
      ).asJava,
    )

    actAndCheck(
      "Venue settles the trade", {
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(venueParty),
            commands = otcTrade.id
              .exerciseOTCTrade_Settle(otcTradeSettleArgs)
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = settlementFactoryWithDisclosures.disclosedContracts,
          )
      },
    )(
      "Alice and Bob's balance reflect the trade",
      _ => {
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(tradingappv2.OTCTrade.COMPANION)(
            venueParty
          ) shouldBe empty withClue "OTCTrade"

        suppressFailedClues(loggerFactory) {
          clue("Check alice's balance") {
            checkBalance(
              aliceWalletClient,
              expectedRound = None,
              expectedUnlockedQtyRange = (
                tapAmount - aliceTransferAmount + bobTransferAmount,
                tapAmount - aliceTransferAmount + bobTransferAmount,
              ),
              expectedLockedQtyRange = (0.0, 0.0),
              expectedHoldingFeeRange = holdingFeesBound,
            )
            checkTxHistory(
              aliceWalletClient,
              Seq(
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(aliceParty.toProtoPrimitive, bobTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(bobParty.toProtoPrimitive, -bobTransferAmount)
                  )
                },
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(bobParty.toProtoPrimitive, aliceTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(aliceParty.toProtoPrimitive, -aliceTransferAmount)
                  )
                },
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                },
              ),
              ignore = {
                case transfer: TransferTxLogEntry =>
                  inside(transfer) { _ =>
                    // ignore merges
                    transfer.receivers.isEmpty && transfer.sender.value.party == aliceParty.toProtoPrimitive
                  }
                case _ => false
              },
            )
          }
          clue("Check bob's balance") {
            checkBalance(
              bobWalletClient,
              expectedRound = None,
              expectedUnlockedQtyRange = (
                tapAmount + aliceTransferAmount - bobTransferAmount,
                tapAmount + aliceTransferAmount - bobTransferAmount,
              ),
              expectedLockedQtyRange = (0.0, 0.0),
              expectedHoldingFeeRange = holdingFeesBound,
            )
            checkTxHistory(
              bobWalletClient,
              Seq(
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(aliceParty.toProtoPrimitive, bobTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(bobParty.toProtoPrimitive, -bobTransferAmount)
                  )
                },
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  logEntry.receivers shouldBe Seq(
                    PartyAndAmount(bobParty.toProtoPrimitive, aliceTransferAmount)
                  )
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(aliceParty.toProtoPrimitive, -aliceTransferAmount)
                  )
                },
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                },
              ),
              ignore = {
                case transfer: TransferTxLogEntry =>
                  inside(transfer) { _ =>
                    // ignore merges
                    transfer.receivers.isEmpty && transfer.sender.value.party == bobParty.toProtoPrimitive
                  }
                case _ => false
              },
            )
          }
        }
      },
    )
  }

  private def setupAllocatedOtcTrade()(implicit env: SpliceTestConsoleEnvironment) = {
    val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
    val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
    // Allocate venue on separate participant node, we still go through the validator API instead of parties.enable
    // so we can use the standard wallet client APIs but give the party a more useful name than splitwell.
    val venuePartyHint = s"venue-party-${Random.nextInt()}"
    val venueParty = splitwellValidatorBackend.onboardUser(
      splitwellWalletClient.config.ledgerApiUser,
      Some(
        PartyId.tryFromProtoPrimitive(
          s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
        )
      ),
    )

    // Setup funds for alice and bob
    actAndCheck("Setup funds for Alice", aliceWalletClient.tap(walletAmuletToUsd(tapAmount)))(
      "Alice's balance",
      _ =>
        checkBalance(
          aliceWalletClient,
          expectedRound = None,
          expectedUnlockedQtyRange = (tapAmount - 1.0, tapAmount + 1.0),
          expectedLockedQtyRange = (0.0, 0.0),
          expectedHoldingFeeRange = holdingFeesBound,
        ),
    )
    actAndCheck("Setup funds for Bob", bobWalletClient.tap(walletAmuletToUsd(tapAmount)))(
      "Bob's balance",
      _ =>
        checkBalance(
          bobWalletClient,
          expectedRound = None,
          expectedUnlockedQtyRange = (tapAmount - 1.0, tapAmount + 1.0),
          expectedLockedQtyRange = (0.0, 0.0),
          expectedHoldingFeeRange = holdingFeesBound,
        ),
    )

    val CreateAllocationRequestResult(
      otcTrade,
      aliceAllocationRequest,
      bobAllocationRequest,
    ) =
      createAllocationRequestV2ViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      )

    def allocate(
        walletClient: WalletAppClientReference,
        allocationRequestView: allocationrequestv2.AllocationRequestView,
    ) = {
      val allocateResponse = clue(s"${walletClient.name} accepts the Allocation Request") {
        walletClient.allocateAmulet(
          new allocationv2.AllocationSpecification(
            allocationRequestView.settlement,
            allocationRequestView.transferLegs,
            allocationRequestView.authorizer,
          )
        )
      }
      allocateResponse.output match {
        case AllocationInstructionResultCompleted(completed) =>
          new allocationv2.Allocation.ContractId(completed.allocationCid)
        case _ =>
          fail(s"Allocation for ${walletClient.name} was not completed: $allocateResponse")
      }
    }

    val aliceAllocation = allocate(aliceWalletClient, aliceAllocationRequest.contract.payload)
    val bobAllocation = allocate(bobWalletClient, bobAllocationRequest.contract.payload)

    AllocatedOtcTrade(
      venueParty,
      aliceParty,
      bobParty,
      aliceAllocationRequest.contract.contractId,
      aliceAllocation,
      bobAllocationRequest.contract.contractId,
      bobAllocation,
      otcTrade,
    )
  }

  "Cancel a DvP and its allocations" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    actAndCheck(
      "Settlement venue cancels the trade", {
        val aliceContext = clue("Get choice context for alice's allocation") {
          val scanResponse =
            sv1ScanBackend.getAllocationV2CancelContext(allocatedOtcTrade.aliceAllocationId)
          aliceValidatorBackend.scanProxy.getAllocationV2CancelContext(
            allocatedOtcTrade.aliceAllocationId
          ) shouldBe scanResponse
          scanResponse
        }
        val bobContext = clue("Get choice context for bob's allocation") {
          sv1ScanBackend.getAllocationV2CancelContext(allocatedOtcTrade.bobAllocationId)
        }

        def mkExtraArg(context: ChoiceContextWithDisclosures) =
          new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)

        val cancelChoice = new tradingappv2.OTCTrade_Cancel(
          java.util.List.of(),
          java.util.List.of(
            new da.types.Tuple2(allocatedOtcTrade.aliceAllocationId, mkExtraArg(aliceContext)),
            new da.types.Tuple2(allocatedOtcTrade.bobAllocationId, mkExtraArg(bobContext)),
          ),
          List(
            allocatedOtcTrade.aliceAllocationRequestId,
            allocatedOtcTrade.bobAllocationRequestId,
          ).map(cid => new tradingappv2.OTCTradeAllocationRequest.ContractId(cid.contractId)).asJava,
        )

        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(allocatedOtcTrade.venueParty),
            commands = allocatedOtcTrade.otcTrade.id
              .exerciseOTCTrade_Cancel(
                cancelChoice
              )
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = aliceContext.disclosedContracts ++ bobContext.disclosedContracts,
          )
      },
    )(
      "Allocations are archived",
      _ => {
        aliceWalletClient
          .listAmuletAllocations() shouldBe empty withClue "Alice's AmuletAllocations"
        bobWalletClient.listAmuletAllocations() shouldBe empty withClue "Bob's AmuletAllocations"
        splitwellValidatorBackend.participantClient.ledger_api.state.acs.of_party(
          party = allocatedOtcTrade.venueParty,
          filterInterfaces = Seq(allocationv2.Allocation.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
        ) shouldBe empty withClue "Allocations"
      },
    )
  }

  "Withdraw an allocation" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    // sanity check
    aliceWalletClient.listAmuletAllocations() should have size (1) withClue "AmuletAllocations"
    actAndCheck(
      "Settlement venue withdraw the trade", {
        aliceWalletClient.withdrawAmuletAllocationV2(
          new amuletallocationV2Codegen.AmuletAllocationV2.ContractId(
            allocatedOtcTrade.aliceAllocationId.contractId
          )
        )
      },
    )(
      "Allocation is archived",
      _ => aliceWalletClient.listAmuletAllocations() shouldBe empty withClue "AmuletAllocations",
    )
  }

  "Reject an allocation request" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    // sanity checks
    aliceWalletClient
      .listAllocationRequests() should have size (1) withClue "alice AllocationRequests"

    actAndCheck(
      "Alice rejects the allocation request", {
        aliceWalletClient.rejectAllocationRequestV2(
          allocatedOtcTrade.aliceAllocationRequestId
        )
      },
    )(
      "Allocation request is archived",
      _ => {
        val aliceRequests = aliceWalletClient.listAllocationRequests()
        aliceRequests shouldBe empty withClue "alice Requests"
      },
    )
  }
}

object TokenStandardV2AllocationIntegrationTest {
  final case class AllocatedOtcTrade(
      venueParty: PartyId,
      aliceParty: PartyId,
      bobParty: PartyId,
      aliceAllocationRequestId: allocationrequestv2.AllocationRequest.ContractId,
      aliceAllocationId: allocationv2.Allocation.ContractId,
      bobAllocationRequestId: allocationrequestv2.AllocationRequest.ContractId,
      bobAllocationId: allocationv2.Allocation.ContractId,
      otcTrade: tradingappv2.OTCTrade.Contract,
  )

  case class CreateAllocationRequestResult(
      trade: tradingappv2.OTCTrade.Contract,
      aliceRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest,
      bobRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest,
  )
}
