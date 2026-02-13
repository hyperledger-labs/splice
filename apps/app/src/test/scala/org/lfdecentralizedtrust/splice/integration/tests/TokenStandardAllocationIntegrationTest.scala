package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.javaapi.data.CreatedEvent
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingapp
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationinstructionv1,
  allocationrequestv1,
  allocationv1,
  holdingv1,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  FactoryChoiceWithDisclosures,
  JavaDecodeUtil,
  TriggerTestUtil,
  WalletTestUtil,
}

import scala.jdk.CollectionConverters.*
import scala.util.Random
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppActivityMarker
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation as amuletallocationCodegen
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0
class TokenStandardAllocationIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil
    with TokenStandardTest {

  import TokenStandardAllocationIntegrationTest.*

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
          backend.participantClient.upload_dar_unless_exists(tokenStandardTestDarPath)
        }
      })
  }

  val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
  val holdingFeesBound = (BigDecimal(0.0), BigDecimal(1.0))
  val tapAmount = walletUsdToAmulet(1000.0)
  val aliceTransferAmount = walletUsdToAmulet(100.0)
  val bobTransferAmount = walletUsdToAmulet(20.0)
  val feesReserveMultiplier = 1.1 // fee reserves are 4 x the fees required for the transfer
  val feesUpperBound = walletUsdToAmulet(1.15)

  def createAllocationCommand(
      participantClient: ParticipantClientReference,
      request: allocationrequestv1.AllocationRequestView,
      legId: String,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): FactoryChoiceWithDisclosures[
    allocationinstructionv1.AllocationFactory.ContractId,
    allocationinstructionv1.AllocationFactory_Allocate,
  ] = {
    val leg = request.transferLegs.get(legId)
    clue(
      s"Creating command to request allocation for leg $legId to transfer ${leg.amount} amulets from ${leg.sender} to ${leg.receiver}"
    ) {
      val sender = PartyId.tryFromProtoPrimitive(leg.sender)
      val senderHoldings =
        participantClient.ledger_api.state.acs.of_party(
          party = sender,
          filterInterfaces = Seq(holdingv1.Holding.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
          includeCreatedEventBlob = true,
        )
      val allocation = new allocationv1.AllocationSpecification(
        request.settlement,
        legId,
        leg,
      )
      val now = env.environment.clock.now.toInstant
      val choiceArgs = new allocationinstructionv1.AllocationFactory_Allocate(
        dsoParty.toProtoPrimitive,
        allocation,
        /*requestedAt =*/ now,
        senderHoldings
          .map(senderHolding => new holdingv1.Holding.ContractId(senderHolding.contractId))
          .asJava,
        new metadatav1.ExtraArgs(
          new metadatav1.ChoiceContext(
            java.util.Map.of()
          ),
          new metadatav1.Metadata(java.util.Map.of()),
        ),
      )
      val factoryChoice0 = sv1ScanBackend.getAllocationFactory(choiceArgs)
      aliceValidatorBackend.scanProxy.getAllocationFactory(choiceArgs) shouldBe factoryChoice0
      factoryChoice0.copy(disclosedContracts = factoryChoice0.disclosedContracts)
    }
  }

  def createAllocation(
      walletClient: WalletAppClientReference,
      request: allocationrequestv1.AllocationRequestView,
      legId: String,
  ): allocationv1.Allocation.ContractId = {
    val transferLeg = request.transferLegs.get(legId)
    val senderParty = PartyId.tryFromProtoPrimitive(transferLeg.sender)
    val (_, allocation) = actAndCheck(
      show"Create allocation for leg $legId with sender $senderParty", {
        walletClient.allocateAmulet(
          new allocationv1.AllocationSpecification(
            request.settlement,
            legId,
            transferLeg,
          )
        )
      },
    )(
      show"There exists an allocation from $senderParty",
      _ => {
        val allocations = walletClient.listAmuletAllocations()
        allocations should have size 1
        allocations.head
      },
    )
    new allocationv1.Allocation.ContractId(allocation.contractId.contractId)
  }

  "Settle a DvP using allocations" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    actAndCheck(
      "Settlement venue settles the trade", {
        val aliceContext = clue("Get choice context for alice's allocation") {
          val scanResponse =
            sv1ScanBackend.getAllocationTransferContext(allocatedOtcTrade.aliceAllocationId)
          aliceValidatorBackend.scanProxy.getAllocationTransferContext(
            allocatedOtcTrade.aliceAllocationId
          ) shouldBe scanResponse
          scanResponse
        }
        // We do this after alice gets her context so one is featured and one isn't.
        actAndCheck("Venue self-features", splitwellWalletClient.selfGrantFeaturedAppRight())(
          "Scan shows featured app right",
          _ =>
            sv1ScanBackend.lookupFeaturedAppRight(allocatedOtcTrade.venueParty) shouldBe a[Some[?]],
        )

        val bobContext = clue("Get choice context for bob's allocation") {
          sv1ScanBackend.getAllocationTransferContext(allocatedOtcTrade.bobAllocationId)
        }

        def mkExtraArg(context: ChoiceContextWithDisclosures) =
          new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)

        val settlementChoice = new tradingapp.OTCTrade_Settle(
          Map(
            "leg0" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
              allocatedOtcTrade.aliceAllocationId,
              mkExtraArg(aliceContext),
            ),
            "leg1" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
              allocatedOtcTrade.bobAllocationId,
              mkExtraArg(bobContext),
            ),
          ).asJava
        )

        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(allocatedOtcTrade.venueParty),
            commands = allocatedOtcTrade.tradeId
              .exerciseOTCTrade_Settle(
                settlementChoice
              )
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = aliceContext.disclosedContracts ++ bobContext.disclosedContracts,
          )
      },
    )(
      "Alice and Bob's balance reflect the trade",
      tree => {
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
          }
        }
        val events = tree.getEventsById().asScala.values
        forExactly(1, events) {
          inside(_) { case c: CreatedEvent =>
            val decoded = JavaDecodeUtil
              .decodeCreated(FeaturedAppActivityMarker.COMPANION)(c)
              .value
            decoded.data.provider shouldBe allocatedOtcTrade.venueParty.toProtoPrimitive
          }
        }
      },
    )
  }

  "Cancel a DvP and its allocations" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    actAndCheck(
      "Settlement venue cancels the trade", {
        val aliceContext = clue("Get choice context for alice's allocation") {
          val scanResponse =
            sv1ScanBackend.getAllocationCancelContext(allocatedOtcTrade.aliceAllocationId)
          aliceValidatorBackend.scanProxy.getAllocationCancelContext(
            allocatedOtcTrade.aliceAllocationId
          ) shouldBe scanResponse
          scanResponse
        }
        val bobContext = clue("Get choice context for bob's allocation") {
          sv1ScanBackend.getAllocationCancelContext(allocatedOtcTrade.bobAllocationId)
        }

        def mkExtraArg(context: ChoiceContextWithDisclosures) =
          new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)

        val cancelChoice = new tradingapp.OTCTrade_Cancel(
          Map(
            "leg0" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
              allocatedOtcTrade.aliceAllocationId,
              mkExtraArg(aliceContext),
            ),
            "leg1" -> new org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2(
              allocatedOtcTrade.bobAllocationId,
              mkExtraArg(bobContext),
            ),
          ).asJava
        )

        splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(allocatedOtcTrade.venueParty),
            commands = allocatedOtcTrade.tradeId
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
      _ =>
        splitwellValidatorBackend.participantClient.ledger_api.state.acs.of_party(
          party = allocatedOtcTrade.venueParty,
          filterInterfaces = Seq(allocationv1.Allocation.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
        ) shouldBe empty,
    )
  }

  "Withdraw an allocation" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    // sanity check
    aliceWalletClient.listAmuletAllocations() should have size (1)
    actAndCheck(
      "Settlement venue withdraw the trade", {
        aliceWalletClient.withdrawAmuletAllocation(
          new amuletallocationCodegen.AmuletAllocation.ContractId(
            allocatedOtcTrade.aliceAllocationId.contractId
          )
        )
      },
    )(
      "Allocation is archived",
      _ => aliceWalletClient.listAmuletAllocations() shouldBe empty,
    )
  }

  "Reject an allocation request" in { implicit env =>
    val allocatedOtcTrade = setupAllocatedOtcTrade()
    // sanity checks
    aliceWalletClient.listAllocationRequests() should have size (1)
    bobWalletClient.listAllocationRequests() should have size (1)

    actAndCheck(
      "Alice rejects the allocation request", {
        aliceWalletClient.rejectAllocationRequest(
          allocatedOtcTrade.tradeId.toInterface(allocationrequestv1.AllocationRequest.INTERFACE)
        )
      },
    )(
      "Allocation request is archived",
      _ => {
        val aliceRequests = aliceWalletClient.listAllocationRequests()
        aliceRequests shouldBe empty
        val bobRequests = bobWalletClient.listAllocationRequests()
        bobRequests shouldBe empty
      },
    )
  }

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

    val CreateAllocationRequestResult(trade, aliceRequest, bobRequest) =
      createAllocationRequestViaOTCTrade(
        aliceParty,
        aliceTransferAmount,
        bobParty,
        bobTransferAmount,
        venueParty,
      )

    val (aliceAllocationId, _) =
      actAndCheck(
        "Alice creates the matching allocation",
        createAllocation(
          aliceWalletClient,
          aliceRequest,
          "leg0",
        ),
      )(
        "Alice's balance after the allocation",
        _ =>
          checkBalance(
            aliceWalletClient,
            expectedRound = None,
            expectedUnlockedQtyRange = (
              tapAmount - aliceTransferAmount * feesReserveMultiplier,
              tapAmount - aliceTransferAmount,
            ),
            expectedLockedQtyRange =
              (aliceTransferAmount, aliceTransferAmount * feesReserveMultiplier),
            expectedHoldingFeeRange = holdingFeesBound,
          ),
      )

    val (bobAllocationId, _) =
      actAndCheck(
        "Bob creates the matching allocation",
        createAllocation(
          bobWalletClient,
          bobRequest,
          "leg1",
        ),
      )(
        "Bob's balance after the allocation",
        _ =>
          checkBalance(
            bobWalletClient,
            expectedRound = None,
            expectedUnlockedQtyRange = (
              tapAmount - bobTransferAmount * feesReserveMultiplier,
              tapAmount - bobTransferAmount,
            ),
            expectedLockedQtyRange = (bobTransferAmount, bobTransferAmount * feesReserveMultiplier),
            expectedHoldingFeeRange = holdingFeesBound,
          ),
      )

    clue("Wait for allocations to be ingested by SV1") {
      // there's no endpoint to list allocations, so call these until they succeed
      eventuallySucceeds() {
        sv1ScanBackend.getAllocationCancelContext(aliceAllocationId)
        sv1ScanBackend.getAllocationCancelContext(bobAllocationId)
      }
    }

    AllocatedOtcTrade(
      venueParty = venueParty,
      aliceParty = aliceParty,
      bobParty = bobParty,
      aliceAllocationId = aliceAllocationId,
      bobAllocationId = bobAllocationId,
      tradeId = trade.id,
    )
  }
}

object TokenStandardAllocationIntegrationTest {
  final case class AllocatedOtcTrade(
      venueParty: PartyId,
      aliceParty: PartyId,
      bobParty: PartyId,
      aliceAllocationId: allocationv1.Allocation.ContractId,
      bobAllocationId: allocationv1.Allocation.ContractId,
      tradeId: tradingapp.OTCTrade.ContractId,
  )
}
