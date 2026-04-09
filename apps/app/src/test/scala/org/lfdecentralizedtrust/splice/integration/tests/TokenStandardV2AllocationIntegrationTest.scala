package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.DamlRecord
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.commands.LedgerApiTypeWrappers.WrappedContractEntry
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationrequestv2,
  allocationv2,
  holdingv2,
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
import org.lfdecentralizedtrust.splice.wallet.store.{
  BalanceChangeTxLogEntry,
  PartyAndAmount,
  TransferTxLogEntry,
  TxLogEntry,
}

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Random

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class TokenStandardV2AllocationIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil {

  protected val tokenStandardV2TestDarPath =
    "token-standard/examples/splice-token-test-trading-app-v2/.daml/dist/splice-token-test-trading-app-v2-current.dar"

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
  val holdingFeesBound = (BigDecimal(0.0), BigDecimal(1.0))
  val tapAmount = walletUsdToAmulet(1000.0)
  val aliceTransferAmount = walletUsdToAmulet(100.0)
  val bobTransferAmount = walletUsdToAmulet(20.0)
  val feesReserveMultiplier = 1.1 // fee reserves are 4 x the fees required for the transfer
  val feesUpperBound = walletUsdToAmulet(1.15)

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
        "OTCTradeProposal",
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
      /* extraReceiptAuthorizers =*/ List(aliceParty, bobParty).map(basicAccount).asJava,
      /*actors = */ java.util.List.of(aliceParty.toProtoPrimitive, bobParty.toProtoPrimitive),
      emptyExtraArgs,
    )
    val settlementFactoryWithDisclosures =
      sv1ScanBackend.getSettlementFactoryV2(
        settleBatch
      )

    val extraAuthorizers =
      otcTrade.data.autoReceiptAuthorizers.asScala.filterNot(_.startsWith("splitwell")).asJava
    val otcTradeSettleArgs = new tradingappv2.OTCTrade_Settle(
      Map[String, tradingappv2.SettlementBatch](
        dsoParty.toProtoPrimitive -> new tradingappv2.settlementbatch.SettlementBatchV2(
          allocations.asJava,
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
      extraAuthorizers,
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
                tapAmount - aliceTransferAmount + bobTransferAmount - feesUpperBound,
                tapAmount - aliceTransferAmount + bobTransferAmount,
              ),
              expectedLockedQtyRange = (0.0, 0.0),
              expectedHoldingFeeRange = holdingFeesBound,
            )
            checkTxHistory(
              aliceWalletClient,
              Seq(
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
                  logEntry.receiver shouldBe aliceParty.toProtoPrimitive
                  logEntry.amount shouldBe bobTransferAmount
                },
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  // No balance is transferred (just locking) here so receivers is empty
                  logEntry.receivers shouldBe Seq.empty
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(aliceParty.toProtoPrimitive, -aliceTransferAmount)
                  )
                },
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                },
              ),
            )
          }
          clue("Check bob's balance") {
            checkBalance(
              bobWalletClient,
              expectedRound = None,
              expectedUnlockedQtyRange = (
                tapAmount + aliceTransferAmount - bobTransferAmount - feesUpperBound,
                tapAmount + aliceTransferAmount - bobTransferAmount,
              ),
              expectedLockedQtyRange = (0.0, 0.0),
              expectedHoldingFeeRange = holdingFeesBound,
            )
            checkTxHistory(
              bobWalletClient,
              Seq(
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Mint.toProto
                  logEntry.receiver shouldBe bobParty.toProtoPrimitive
                  logEntry.amount shouldBe aliceTransferAmount
                },
                { case logEntry: TransferTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.TransferTransactionSubtype.Transfer.toProto
                  // No balance is transferred (just locking) here so receivers is empty
                  logEntry.receivers shouldBe Seq.empty
                  logEntry.sender shouldBe Some(
                    PartyAndAmount(bobParty.toProtoPrimitive, -bobTransferAmount)
                  )
                },
                { case logEntry: BalanceChangeTxLogEntry =>
                  logEntry.subtype.value shouldBe TxLogEntry.BalanceChangeTransactionSubtype.Tap.toProto
                },
              ),
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
      (aliceAllocationRequestCid, aliceAllocationRequest),
      (bobAllocationRequestCid, bobAllocationRequest),
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
      val (allocateResponse, _) = actAndCheck(
        s"${walletClient.name} accepts the Allocation Request", {
          val allocateResponse = walletClient.allocateAmulet(
            new allocationv2.AllocationSpecification(
              allocationRequestView.settlement,
              allocationRequestView.transferLegs,
              allocationRequestView.authorizer,
            )
          )
          allocateResponse
        },
      )(
        "The Allocation Request is gone",
        _ => {
          // TODO (#4912): use the listAllocationRequests call
          // TODO (#4914): the instructions are not being archived by the allocation, so this check won't succeed yet
//          participant.ledger_api.state.acs
//            .of_party(
//              party = bobParty,
//              filterInterfaces =
//                Seq(allocationrequestv2.AllocationRequest.TEMPLATE_ID).map(templateId =>
//                  TemplateId(
//                    templateId.getPackageId,
//                    templateId.getModuleName,
//                    templateId.getEntityName,
//                  )
//                ),
//            ) shouldBe empty
          succeed
        },
      )
      allocateResponse.output match {
        case AllocationInstructionResultCompleted(completed) =>
          new allocationv2.Allocation.ContractId(completed.allocationCid)
        case _ =>
          fail(s"Allocation for ${walletClient.name} was not completed: $allocateResponse")
      }
    }

    val aliceAllocation = allocate(aliceWalletClient, aliceAllocationRequest)
    val bobAllocation = allocate(bobWalletClient, bobAllocationRequest)

    AllocatedOtcTrade(
      venueParty,
      aliceParty,
      bobParty,
      aliceAllocationRequestCid,
      aliceAllocation,
      bobAllocationRequestCid,
      bobAllocation,
      otcTrade,
    )
  }

  def createAllocationRequestV2ViaOTCTrade(
      aliceParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobParty: PartyId,
      bobTransferAmount: BigDecimal,
      venueParty: PartyId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): CreateAllocationRequestResult = {
    val (_, otcTrade) = actAndCheck(
      "Venue creates OTC Trade", {
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(venueParty),
            commands = mkTestTradeProposal(
              dsoParty,
              venueParty,
              aliceParty,
              aliceTransferAmount,
              bobParty,
              bobTransferAmount,
            )
              .create()
              .commands()
              .asScala
              .toSeq,
          )
      },
    )(
      "There exists a trade proposal visible to the venue's participant",
      _ =>
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(tradingappv2.OTCTrade.COMPANION)(
            venueParty
          ),
    )

    val (_, (bobAllocationRequest, aliceAllocationRequest)) = actAndCheck(
      "Venue creates allocation requests", {
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(venueParty),
            commands = otcTrade.id
              .exerciseOTCTrade_RequestAllocations()
              .commands()
              .asScala
              .toSeq,
          )
      },
    )(
      "Sender and receiver see the allocation requests",
      _ => {
        // TODO (#4912): use the listAllocationRequests call
        val bobAllocationRequest =
          bobValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
            .of_party(
              party = bobParty,
              filterInterfaces =
                Seq(allocationrequestv2.AllocationRequest.TEMPLATE_ID).map(templateId =>
                  TemplateId(
                    templateId.getPackageId,
                    templateId.getModuleName,
                    templateId.getEntityName,
                  )
                ),
            )
            .loneElement
        val aliceAllocationRequest =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
            .of_party(
              party = aliceParty,
              filterInterfaces =
                Seq(allocationrequestv2.AllocationRequest.TEMPLATE_ID).map(templateId =>
                  TemplateId(
                    templateId.getPackageId,
                    templateId.getModuleName,
                    templateId.getEntityName,
                  )
                ),
            )
            .loneElement

        def toView(ledgerAllocationRequest: WrappedContractEntry) = {
          val viewValue = ledgerAllocationRequest.event.interfaceViews.loneElement.viewValue
            .valueOrFail(s"AllocationRequest $ledgerAllocationRequest didn't have a view")
          new allocationrequestv2.AllocationRequest.ContractId(
            ledgerAllocationRequest.contractId
          ) -> allocationrequestv2.AllocationRequestView
            .valueDecoder()
            .decode(
              DamlRecord.fromProto(
                com.daml.ledger.api.v2.value.Record.toJavaProto(viewValue)
              )
            )
        }

        (toView(bobAllocationRequest), toView(aliceAllocationRequest))
      },
    )

    CreateAllocationRequestResult(otcTrade, aliceAllocationRequest, bobAllocationRequest)
  }

  def mkTestTradeProposal(
      dso: PartyId,
      venue: PartyId,
      alice: PartyId,
      aliceTransferAmount: BigDecimal,
      bob: PartyId,
      bobTransferAmount: BigDecimal,
  ): tradingappv2.OTCTrade = {
    val aliceLeg = mkTransferLeg("leg0", dso, alice, bob, aliceTransferAmount)
    // TODO(#561): swap against a token from the token reference implementation
    val bobLeg = mkTransferLeg("leg1", dso, bob, alice, bobTransferAmount)
    new tradingappv2.OTCTrade(
      venue.toProtoPrimitive,
      Seq(aliceLeg, bobLeg).asJava,
      Instant.now(),
      // settleAt:
      // - Allocations should be made before this time.
      // Settlement happens at any point after this time.
      Instant.now().plusSeconds(30L),
      java.util.Optional.empty,
      /*autoReceiptAuthorizers=*/ java.util.List.of(),
    )
  }

  def mkTransferLeg(
      legId: String,
      dso: PartyId,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  ): allocationv2.TransferLeg =
    new allocationv2.TransferLeg(
      legId,
      basicAccount(sender),
      basicAccount(receiver),
      amount.bigDecimal,
      new holdingv2.InstrumentId(dso.toProtoPrimitive, "Amulet"),
      new metadatav1.Metadata(java.util.Map.of("some_leg_meta", UUID.randomUUID().toString)),
    )

  def basicAccount(party: PartyId): holdingv2.Account =
    new holdingv2.Account(
      party.toProtoPrimitive,
      java.util.Optional.empty(),
      java.util.Optional.empty(),
    )
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
      aliceRequest: (
          allocationrequestv2.AllocationRequest.ContractId,
          allocationrequestv2.AllocationRequestView,
      ),
      bobRequest: (
          allocationrequestv2.AllocationRequest.ContractId,
          allocationrequestv2.AllocationRequestView,
      ),
  )
}
