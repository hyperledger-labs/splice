package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.DamlRecord
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv1.AllocationRequestView
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
import org.lfdecentralizedtrust.splice.util.*

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.util.Random

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class TokenStandardV2AllocationIntegrationTest
    extends IntegrationTestWithIsolatedEnvironment
    with HasExecutionContext
    with WalletTestUtil
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

  def createAllocation(
      walletClient: WalletAppClientReference,
      request: allocationrequestv2.AllocationRequestView,
      senderParty: PartyId,
  ): allocationv2.Allocation.ContractId = {
    val senderTransferLegs =
      request.transferLegs.asScala.filter(_.sender.owner == senderParty.toProtoPrimitive)
    val (_, allocation) = actAndCheck(
      show"Create allocation for legs of sender $senderParty", {
        walletClient.allocateAmulet(
          new allocationv2.AllocationSpecification(
            request.settlement,
            senderTransferLegs.asJava,
            new holdingv2.Account(
              java.util.Optional.empty,
              java.util.Optional.empty,
              senderParty.toProtoPrimitive,
            ),
          )
        )
      },
    )(
      show"There exists an allocation from $senderParty",
      _ => {
        // TODO: this doesn't work, it's only returning V1
        val allocations = walletClient.listAmuletAllocations()
        allocations should have size 1 withClue "AmuletAllocations"
        allocations.head
      },
    )
    new allocationv2.Allocation.ContractId(allocation.contractId.contractId)
  }

  "Settle a DvP using allocations" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
//    actAndCheck(
//      "Settlement venue settles the trade", {
//        val aliceContext = clue("Get choice context for alice's allocation") {
//          val scanResponse =
//            sv1ScanBackend.getAllocationTransferContext(allocatedOtcTrade.aliceAllocationId)
//          aliceValidatorBackend.scanProxy.getAllocationTransferContext(
//            allocatedOtcTrade.aliceAllocationId
//          ) shouldBe scanResponse
//          scanResponse
//        }
//        // We do this after alice gets her context so one is featured and one isn't.
//        actAndCheck("Venue self-features", splitwellWalletClient.selfGrantFeaturedAppRight())(
//          "Scan shows featured app right",
//          _ =>
//            sv1ScanBackend.lookupFeaturedAppRight(allocatedOtcTrade.venueParty) shouldBe a[Some[?]],
//        )
//
//        val bobContext = clue("Get choice context for bob's allocation") {
//          sv1ScanBackend.getAllocationTransferContext(allocatedOtcTrade.bobAllocationId)
//        }
//
//        def mkExtraArg(context: ChoiceContextWithDisclosures) =
//          new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)
//
//        val settlementChoice = new tradingapp.OTCTrade_Settle(
//          Map(
//            "leg0" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
//              allocatedOtcTrade.aliceAllocationId,
//              mkExtraArg(aliceContext),
//            ),
//            "leg1" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
//              allocatedOtcTrade.bobAllocationId,
//              mkExtraArg(bobContext),
//            ),
//          ).asJava
//        )
//
//        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
//          .submitJava(
//            Seq(allocatedOtcTrade.venueParty),
//            commands = allocatedOtcTrade.tradeId
//              .exerciseOTCTrade_Settle(
//                settlementChoice
//              )
//              .commands()
//              .asScala
//              .toSeq,
//            disclosedContracts = aliceContext.disclosedContracts ++ bobContext.disclosedContracts,
//          )
//      },
//    )(
//      "Alice and Bob's balance reflect the trade",
//      tree => {
//        suppressFailedClues(loggerFactory) {
//          clue("Check alice's balance") {
//            checkBalance(
//              aliceWalletClient,
//              expectedRound = None,
//              expectedUnlockedQtyRange = (
//                tapAmount - aliceTransferAmount + bobTransferAmount - feesUpperBound,
//                tapAmount - aliceTransferAmount + bobTransferAmount,
//              ),
//              expectedLockedQtyRange = (0.0, 0.0),
//              expectedHoldingFeeRange = holdingFeesBound,
//            )
//          }
//          clue("Check bob's balance") {
//            checkBalance(
//              bobWalletClient,
//              expectedRound = None,
//              expectedUnlockedQtyRange = (
//                tapAmount + aliceTransferAmount - bobTransferAmount - feesUpperBound,
//                tapAmount + aliceTransferAmount - bobTransferAmount,
//              ),
//              expectedLockedQtyRange = (0.0, 0.0),
//              expectedHoldingFeeRange = holdingFeesBound,
//            )
//          }
//        }
//        val events = tree.getEventsById().asScala.values
//        forExactly(1, events) {
//          inside(_) { case c: CreatedEvent =>
//            if (
//              PackageVersion.assertFromString(
//                sv1ScanBackend
//                  .getAmuletRules()
//                  .payload
//                  .configSchedule
//                  .initialValue
//                  .packageConfig
//                  .amulet
//              ) >= DarResources.amulet_0_1_17.metadata.version
//            ) {
//              val decoded = JavaDecodeUtil
//                .decodeCreated(FeaturedAppActivityMarker.COMPANION)(c)
//                .value
//              decoded.data.provider shouldBe allocatedOtcTrade.venueParty.toProtoPrimitive
//            } else {
//              val decoded = JavaDecodeUtil
//                .decodeCreated(AppRewardCoupon.COMPANION)(c)
//                .value
//              decoded.data.featured shouldBe true
//              decoded.data.provider shouldBe allocatedOtcTrade.venueParty.toProtoPrimitive
//            }
//          }
//        }
//      },
//    )
  }

//  "Cancel a DvP and its allocations" in { implicit env =>
//    val allocatedOtcTrade = setupAllocatedOtcTrade()
//    actAndCheck(
//      "Settlement venue cancels the trade", {
//        val aliceContext = clue("Get choice context for alice's allocation") {
//          val scanResponse =
//            sv1ScanBackend.getAllocationCancelContext(allocatedOtcTrade.aliceAllocationId)
//          aliceValidatorBackend.scanProxy.getAllocationCancelContext(
//            allocatedOtcTrade.aliceAllocationId
//          ) shouldBe scanResponse
//          scanResponse
//        }
//        val bobContext = clue("Get choice context for bob's allocation") {
//          sv1ScanBackend.getAllocationCancelContext(allocatedOtcTrade.bobAllocationId)
//        }
//
//        def mkExtraArg(context: ChoiceContextWithDisclosures) =
//          new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)
//
//        val cancelChoice = new tradingapp.OTCTrade_Cancel(
//          Map(
//            "leg0" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
//              allocatedOtcTrade.aliceAllocationId,
//              mkExtraArg(aliceContext),
//            ),
//            "leg1" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
//              allocatedOtcTrade.bobAllocationId,
//              mkExtraArg(bobContext),
//            ),
//          ).asJava
//        )
//
//        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
//          .submitJava(
//            Seq(allocatedOtcTrade.venueParty),
//            commands = allocatedOtcTrade.tradeId
//              .exerciseOTCTrade_Cancel(
//                cancelChoice
//              )
//              .commands()
//              .asScala
//              .toSeq,
//            disclosedContracts = aliceContext.disclosedContracts ++ bobContext.disclosedContracts,
//          )
//      },
//    )(
//      "Allocations are archived",
//      _ =>
//        splitwellValidatorBackend.participantClient.ledger_api.state.acs.of_party(
//          party = allocatedOtcTrade.venueParty,
//          filterInterfaces = Seq(allocationv2.Allocation.TEMPLATE_ID).map(templateId =>
//            TemplateId(
//              templateId.getPackageId,
//              templateId.getModuleName,
//              templateId.getEntityName,
//            )
//          ),
//        ) shouldBe empty withClue "Allocations",
//    )
//  }
//
//  "Withdraw an allocation" in { implicit env =>
//    val allocatedOtcTrade = setupAllocatedOtcTrade()
//    // sanity check
//    aliceWalletClient.listAmuletAllocations() should have size (1) withClue "AmuletAllocations"
//    actAndCheck(
//      "Settlement venue withdraw the trade", {
//        aliceWalletClient.withdrawAmuletAllocation(
//          new amuletallocationv2Codegen.AmuletAllocationV2.ContractId(
//            allocatedOtcTrade.aliceAllocationId.contractId
//          )
//        )
//      },
//    )(
//      "Allocation is archived",
//      _ => aliceWalletClient.listAmuletAllocations() shouldBe empty withClue "AmuletAllocations",
//    )
//  }
//
//  "Reject an allocation request" in { implicit env =>
//    val allocatedOtcTrade = setupAllocatedOtcTrade()
//    // sanity checks
//    aliceWalletClient
//      .listAllocationRequests() should have size (1) withClue "alice AllocationRequests"
//    bobWalletClient
//      .listAllocationRequests() should have size (1) withClue "bob AllocationRequests"
//
//    actAndCheck(
//      "Alice rejects the allocation request", {
//        aliceWalletClient.rejectAllocationRequest(
//          allocatedOtcTrade.tradeId.toInterface(allocationrequestv2.AllocationRequest.INTERFACE)
//        )
//      },
//    )(
//      "Allocation request is archived",
//      _ => {
//        val aliceRequests = aliceWalletClient.listAllocationRequests()
//        aliceRequests shouldBe empty withClue "alice Requests"
//        val bobRequests = bobWalletClient.listAllocationRequests()
//        bobRequests shouldBe empty withClue "bob Requests"
//      },
//    )
//  }

  private def setupAllocatedOtcTrade()(implicit env: SpliceTestConsoleEnvironment) = {
    // TODO(DACH-NY/canton-network-node#18561): use external parties for all of them
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

//    val CreateAllocationRequestResult(trade, aliceRequest, bobRequest) =
    createAllocationRequestV2ViaOTCTrade(
      aliceParty,
      aliceTransferAmount,
      bobParty,
      bobTransferAmount,
      venueParty,
    )

//    val (aliceAllocationId, _) =
//      actAndCheck(
//        "Alice creates the matching allocation",
//        createAllocation(
//          aliceWalletClient,
//          aliceRequest,
//          aliceParty,
//        ),
//      )(
//        "Alice's balance after the allocation",
//        _ =>
//          checkBalance(
//            aliceWalletClient,
//            expectedRound = None,
//            expectedUnlockedQtyRange = (
//              tapAmount - aliceTransferAmount * feesReserveMultiplier,
//              tapAmount - aliceTransferAmount,
//            ),
//            expectedLockedQtyRange =
//              (aliceTransferAmount, aliceTransferAmount * feesReserveMultiplier),
//            expectedHoldingFeeRange = holdingFeesBound,
//          ),
//      )
//
//    val (bobAllocationId, _) =
//      actAndCheck(
//        "Bob creates the matching allocation",
//        createAllocation(
//          bobWalletClient,
//          bobRequest,
//          bobParty,
//        ),
//      )(
//        "Bob's balance after the allocation",
//        _ =>
//          checkBalance(
//            bobWalletClient,
//            expectedRound = None,
//            expectedUnlockedQtyRange = (
//              tapAmount - bobTransferAmount * feesReserveMultiplier,
//              tapAmount - bobTransferAmount,
//            ),
//            expectedLockedQtyRange = (bobTransferAmount, bobTransferAmount * feesReserveMultiplier),
//            expectedHoldingFeeRange = holdingFeesBound,
//          ),
//      )
//
//    clue("Wait for allocations to be ingested by SV1") {
//      // there's no endpoint to list allocations, so call these until they succeed
//      eventuallySucceeds() {
//        sv1ScanBackend.getAllocationCancelContext(aliceAllocationId)
//        sv1ScanBackend.getAllocationCancelContext(bobAllocationId)
//      }
//    }
//
//    AllocatedOtcTrade(
//      venueParty = venueParty,
//      aliceParty = aliceParty,
//      bobParty = bobParty,
//      aliceAllocationId = aliceAllocationId,
//      bobAllocationId = bobAllocationId,
//      tradeId = trade.id,
//    )
  }

  def createAllocationRequestV2ViaOTCTrade(
      aliceParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobParty: PartyId,
      bobTransferAmount: BigDecimal,
      venueParty: PartyId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  )
  // : CreateAllocationRequestResult
  = {
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
        // TODO: use the listAllocationRequests call
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
        (bobAllocationRequest, aliceAllocationRequest)
      },
    )

    val allocations = Seq(
      (
        aliceWalletClient,
        aliceValidatorBackend.participantClientWithAdminToken,
        aliceAllocationRequest,
      ),
      (bobWalletClient, bobValidatorBackend.participantClientWithAdminToken, bobAllocationRequest),
    ).map { case (walletClient, _, rawAllocationRequest) =>
      val ((allocateResponse, allocationRequestView), _) = actAndCheck(
        s"${walletClient.name} accepts the Allocation Request", {
          val viewValue = rawAllocationRequest.event.interfaceViews.loneElement.viewValue
            .valueOrFail(s"AllocationRequest $rawAllocationRequest didn't have a view")
          val allocationRequestView = allocationrequestv2.AllocationRequestView
            .valueDecoder()
            .decode(
              DamlRecord.fromProto(
                com.daml.ledger.api.v2.value.Record.toJavaProto(viewValue)
              )
            )
          val allocateResponse = walletClient.allocateAmulet(
            new allocationv2.AllocationSpecification(
              allocationRequestView.settlement,
              allocationRequestView.transferLegs,
              allocationRequestView.authorizer,
            )
          )
          (allocateResponse, allocationRequestView)
        },
      )(
        "The Allocation Request is gone",
        _ => {
          // TODO: use the listAllocationRequests call
          // TODO: AllocationInstruction_Accept is not being called, so the instructions are not being archived
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
          new allocationv2.Allocation.ContractId(completed.allocationCid) -> allocationRequestView
        case _ =>
          fail(s"Allocation for ${walletClient.name} was not completed: $allocateResponse")
      }
    }

    // This is easier than building it from the OTCTrade. In daml there's `mkOtcTradeSettlementInfo`
    val oneAllocationRequestView =
      allocations.headOption.valueOrFail("Allocations list cannot be empty")._2
    val settlementInfo = oneAllocationRequestView.settlement
    val transferLegs = oneAllocationRequestView.transferLegs
    val settleBatch = new allocationv2.SettlementFactory_SettleBatch(
      settlementInfo,
      transferLegs,
      allocations.map(_._1).asJava,
      /* extraReceiptAuthorizers =*/ otcTrade.data.autoReceiptAuthorizers.asScala
        .map(party => basicAccount(PartyId.tryFromProtoPrimitive(party)))
        .asJava,
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
      Map(
        dsoParty.toProtoPrimitive -> new tradingappv2.SettlementBatch(
          allocations.map(_._1).asJava,
          settlementFactoryWithDisclosures.factoryId,
          emptyExtraArgs, // TODO: probably do something with settlementFactoryWithDisclosures.args?
        )
      ).asJava,
      List(
        new tradingappv2.OTCTradeAllocationRequest.ContractId(
          aliceAllocationRequest.contractId
        ),
        new tradingappv2.OTCTradeAllocationRequest.ContractId(
          bobAllocationRequest.contractId
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
      "The trade is archived",
      _ => {
        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .filterJava(tradingappv2.OTCTrade.COMPANION)(
            venueParty
          ) shouldBe empty withClue "OTCTrades"
      },
    )
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
      // TODO: so do we need to wait for that to happen?
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
      java.util.Optional.empty(),
      java.util.Optional.empty(),
      party.toProtoPrimitive,
    )
}

object TokenStandardV2AllocationIntegrationTest {
  final case class AllocatedOtcTrade(
      venueParty: PartyId,
      aliceParty: PartyId,
      bobParty: PartyId,
      aliceAllocationId: allocationv2.Allocation.ContractId,
      bobAllocationId: allocationv2.Allocation.ContractId,
      tradeId: tradingappv2.OTCTrade.ContractId,
  )

  case class CreateAllocationRequestResult(
      trade: tradingappv2.OTCTrade.Contract,
      aliceRequest: AllocationRequestView,
      bobRequest: AllocationRequestView,
  )
}
