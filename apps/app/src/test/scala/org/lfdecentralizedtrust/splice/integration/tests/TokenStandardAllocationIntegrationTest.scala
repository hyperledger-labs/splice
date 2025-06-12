package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.value.Identifier
import com.daml.ledger.api.v2
import com.daml.ledger.javaapi
import com.daml.ledger.javaapi.data.CreatedEvent
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

import java.time.Instant
import java.time.temporal.ChronoUnit
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.util.Random
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.AppRewardCoupon
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv1.AllocationRequestView
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

@org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceAmulet_0_1_9
class TokenStandardAllocationIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with TriggerTestUtil
    with ExternallySignedPartyTestUtil {

  import TokenStandardAllocationIntegrationTest.*

  private val darPath =
    "token-standard/splice-token-standard-test/.daml/dist/splice-token-standard-test-current.dar"

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidatorBackend.participantClient.upload_dar_unless_exists(darPath)
        bobValidatorBackend.participantClient.upload_dar_unless_exists(darPath)
        splitwellValidatorBackend.participantClient.upload_dar_unless_exists(darPath)
      })
  }

  val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
  val holdingFeesBound = (BigDecimal(0.0), BigDecimal(1.0))
  val tapAmount = walletUsdToAmulet(1000.0)
  val aliceTransferAmount = walletUsdToAmulet(100.0)
  val bobTransferAmount = walletUsdToAmulet(20.0)
  val feesReserveMultiplier = 1.1 // fee reserves are 4 x the fees required for the transfer
  val feesUpperBound = walletUsdToAmulet(1.15)

  def mkTransferLeg(
      dso: PartyId,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  ): allocationv1.TransferLeg =
    new allocationv1.TransferLeg(
      sender.toProtoPrimitive,
      receiver.toProtoPrimitive,
      amount.bigDecimal,
      new holdingv1.InstrumentId(dso.toProtoPrimitive, "Amulet"),
      emptyMetadata,
    )

  def mkTestTradeProposal(
      dso: PartyId,
      venue: PartyId,
      alice: PartyId,
      bob: PartyId,
  ): tradingapp.OTCTradeProposal = {
    val aliceLeg = mkTransferLeg(dso, alice, bob, aliceTransferAmount)
    // TODO(#561): swap against a token from the token reference implementation
    val bobLeg = mkTransferLeg(dso, bob, alice, bobTransferAmount)
    new tradingapp.OTCTradeProposal(
      venue.toProtoPrimitive,
      None.toJava,
      Map("leg0" -> aliceLeg, "leg1" -> bobLeg).asJava,
      Seq(alice.toProtoPrimitive).asJava,
    )
  }

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
            Identifier(
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
      participantClient: ParticipantClientReference,
      request: allocationrequestv1.AllocationRequestView,
      legId: String,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): allocationv1.Allocation.ContractId = {
    val senderParty = PartyId.tryFromProtoPrimitive(request.transferLegs.get(legId).sender)
    val (_, allocation) = actAndCheck(
      show"Create allocation for leg $legId with sender $senderParty", {
        val factoryChoice = createAllocationCommand(
          participantClient,
          request,
          legId,
        )
        participantClient.ledger_api_extensions.commands
          .submitJava(
            Seq(senderParty),
            commands = factoryChoice.factoryId
              .exerciseAllocationFactory_Allocate(factoryChoice.args)
              .commands
              .asScala
              .toSeq,
            disclosedContracts = factoryChoice.disclosedContracts,
          )
      },
    )(
      show"There exists an allocation from $senderParty",
      _ => {
        val allocations =
          participantClient.ledger_api.state.acs.of_party(
            party = senderParty,
            filterInterfaces = Seq(allocationv1.Allocation.TEMPLATE_ID).map(templateId =>
              Identifier(
                templateId.getPackageId,
                templateId.getModuleName,
                templateId.getEntityName,
              )
            ),
          )
        allocations should have size (1)
        allocations.head
      },
    )
    new allocationv1.Allocation.ContractId(allocation.event.contractId)
  }

  def listAllocationRequests(
      participantClient: ParticipantClientReference,
      senderParty: PartyId,
  ): Seq[AllocationRequestView] = {
    clue(show"Retrieves allocation requests for $senderParty") {
      val allocationRequests =
        participantClient.ledger_api.state.acs.of_party(
          party = senderParty,
          filterInterfaces =
            Seq(allocationrequestv1.AllocationRequest.TEMPLATE_ID).map(templateId =>
              Identifier(
                templateId.getPackageId,
                templateId.getModuleName,
                templateId.getEntityName,
              )
            ),
        )
      allocationRequests.map(allocationRequest => {
        val requestViewRaw = (allocationRequest.event.interfaceViews.head.viewValue
          .getOrElse(throw new RuntimeException("expected an interface view to be present")))
        allocationrequestv1.AllocationRequestView
          .valueDecoder()
          .decode(
            javaapi.data.DamlRecord.fromProto(
              v2.value.Record.toJavaProto(requestViewRaw)
            )
          )
      })
    }
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
            sv1ScanBackend.lookupFeaturedAppRight(allocatedOtcTrade.venueParty) shouldBe a[Some[_]],
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
              .decodeCreated(AppRewardCoupon.COMPANION)(c)
              .value
            decoded.data.featured shouldBe true
          }
        }
        forExactly(1, events) {
          inside(_) { case c: CreatedEvent =>
            val decoded = JavaDecodeUtil
              .decodeCreated(AppRewardCoupon.COMPANION)(c)
              .value
            decoded.data.featured shouldBe false
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
            Identifier(
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
    actAndCheck(
      "Settlement venue withdraw the trade", {
        val aliceContext = clue("Get choice context for alice's allocation") {
          val scanResponse =
            sv1ScanBackend.getAllocationWithdrawContext(allocatedOtcTrade.aliceAllocationId)
          aliceValidatorBackend.scanProxy.getAllocationWithdrawContext(
            allocatedOtcTrade.aliceAllocationId
          ) shouldBe scanResponse
          scanResponse
        }

        def mkExtraArg(context: ChoiceContextWithDisclosures) =
          new metadatav1.ExtraArgs(context.choiceContext, emptyMetadata)

        val withdrawChoice = new allocationv1.Allocation_Withdraw(
          mkExtraArg(aliceContext)
        )

        aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            Seq(allocatedOtcTrade.aliceParty),
            commands = allocatedOtcTrade.aliceAllocationId
              .exerciseAllocation_Withdraw(
                withdrawChoice
              )
              .commands()
              .asScala
              .toSeq,
            disclosedContracts = aliceContext.disclosedContracts,
          )
      },
    )(
      "Allocation is archived",
      _ =>
        aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs.of_party(
          party = allocatedOtcTrade.aliceParty,
          filterInterfaces = Seq(allocationv1.Allocation.TEMPLATE_ID).map(templateId =>
            Identifier(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
        ) shouldBe empty,
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

    // Alice creates the TestTradeProposal
    val (_, aliceProposal) =
      actAndCheck(
        "Create test OTC Trade Proposal", {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(aliceParty),
              commands = mkTestTradeProposal(dsoParty, venueParty, aliceParty, bobParty)
                .create()
                .commands()
                .asScala
                .toSeq,
            )

        },
      )(
        "There exists a trade proposal visible to both bob's and the venue's participants",
        _ => {
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(tradingapp.OTCTradeProposal.COMPANION)(
              bobParty
            )
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(tradingapp.OTCTradeProposal.COMPANION)(
              bobParty
            )
        },
      )

    // Bob accepts
    val (_, acceptedProposal) =
      actAndCheck(
        "Bob accepts alice's trade proposal", {
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(bobParty),
              commands = aliceProposal.id
                .exerciseOTCTradeProposal_Accept(
                  bobParty.toProtoPrimitive
                )
                .commands()
                .asScala
                .toSeq,
            )

        },
      )(
        "There exists a new trade proposal",
        _ => {
          bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(tradingapp.OTCTradeProposal.COMPANION)(
              bobParty
            )
        },
      )

    // Venue initiates settlement
    val prepareUntil = Instant.now().plus(10, ChronoUnit.MINUTES)
    val settleUntil = prepareUntil.plus(10, ChronoUnit.MINUTES)

    val (_, (trade, aliceRequest, bobRequest)) =
      actAndCheck(
        "Venue initiates settlement", {
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(venueParty),
              commands = acceptedProposal.id
                .exerciseOTCTradeProposal_InitiateSettlement(
                  prepareUntil,
                  settleUntil,
                )
                .commands()
                .asScala
                .toSeq,
            )

        },
      )(
        "There exists an OTCTrade visible as an allocation request to Alice and Bob",
        _ =>
          suppressFailedClues(loggerFactory) {
            val trade =
              splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
                .awaitJava(tradingapp.OTCTrade.COMPANION)(
                  venueParty
                )
            val aliceRequest = clue("Alice sees the allocation request") {
              val requests = listAllocationRequests(
                aliceValidatorBackend.participantClientWithAdminToken,
                aliceParty,
              )
              val request = requests.loneElement
              request.transferLegs.asScala should have size (2)
              request
            }
            val bobRequest = clue("Bob sees the allocation request") {
              val requests = listAllocationRequests(
                bobValidatorBackend.participantClientWithAdminToken,
                bobParty,
              )
              val request = requests.loneElement
              request.transferLegs.asScala should have size (2)
              request
            }
            (trade, aliceRequest, bobRequest)
          },
      )

    val (aliceAllocationId, _) =
      actAndCheck(
        "Alice creates the matching allocation",
        createAllocation(
          aliceValidatorBackend.participantClientWithAdminToken,
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
          bobValidatorBackend.participantClientWithAdminToken,
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
