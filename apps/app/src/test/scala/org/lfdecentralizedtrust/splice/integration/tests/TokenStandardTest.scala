package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2
import com.daml.ledger.javaapi
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationrequestv1.AllocationRequestView
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationv1,
  holdingv1,
  metadatav1,
  transferinstructionv1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingapp
import org.lfdecentralizedtrust.splice.console.LedgerApiExtensions.RichPartyId
import org.lfdecentralizedtrust.splice.console.{
  ParticipantClientReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.util.{FactoryChoiceWithDisclosures, TokenStandardMetadata}
import org.lfdecentralizedtrust.tokenstandard.transferinstruction

import java.time.temporal.ChronoUnit
import java.time.{Duration, Instant}
import java.util.UUID
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

trait TokenStandardTest extends ExternallySignedPartyTestUtil {

  // We upload the current file w/o respecting the initial package config used for Daml compatibility tests,
  // as we don't want to check upgrade compatibility for splice-token-test-trading-app. This does not conflict
  // with checking upgrade compatibility for splice-amulet, as the splice-token-test-trading-app does
  // not statically link with splice-amulet.
  // Tests that use this will require the annotation
  // `org.lfdecentralizedtrust.splice.util.scalatesttags.SpliceTokenTestTradingApp_1_0_0`
  protected val tokenStandardTestDarPath =
    "token-standard/examples/splice-token-test-trading-app/.daml/dist/splice-token-test-trading-app-current.dar"

  val emptyExtraArgs =
    org.lfdecentralizedtrust.splice.util.ChoiceContextWithDisclosures.emptyExtraArgs

  def executeTransferViaTokenStandard(
      participant: ParticipantClientReference,
      sender: RichPartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
      advanceTimeBeforeExecute: Option[Duration] = None,
      description: Option[String] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    actAndCheck(
      s"Instructing transfer of $amount amulets via token standard from $sender to $receiver", {
        val (factoryChoice, senderHoldingCids) = transferViaTokenStandardCommands(
          participant,
          sender.partyId,
          receiver,
          amount,
          expectedKind,
          timeToLife,
          description,
        )
        participant.ledger_api_extensions.commands
          .submitJavaExternalOrLocal(
            sender,
            commands = factoryChoice.factoryId
              .exerciseTransferFactory_Transfer(factoryChoice.args)
              .commands
              .asScala
              .toSeq,
            disclosedContracts = factoryChoice.disclosedContracts,
            expectedTimeBounds = expectedTimeBounds,
            advanceTimeBeforeExecute = advanceTimeBeforeExecute,
          )
        senderHoldingCids.head
      },
    )(
      // Prepared tx execution does not wait for the tx being committed.
      // We thus wait here, as otherwise multiple transfer commands will use the same input holdings.
      "Wait until we see at least one of the input holdings being consumed",
      trackingHoldingCid => {
        participant.ledger_api.event_query
          .by_contract_id(trackingHoldingCid.contractId, requestingParties = Seq(sender.partyId))
          .archived should not be empty
      },
    )
  }

  def transferViaTokenStandardCommands(
      participant: ParticipantClientReference,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
      expectedKind: transferinstruction.v1.definitions.TransferFactoryWithChoiceContext.TransferKind,
      timeToLife: Duration = Duration.ofMinutes(10),
      description: Option[String] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): (
      FactoryChoiceWithDisclosures[
        transferinstructionv1.TransferFactory.ContractId,
        transferinstructionv1.TransferFactory_Transfer,
      ],
      Seq[holdingv1.Holding.ContractId],
  ) = {
    val now = env.environment.clock.now.toInstant
    def unlocked(optLock: java.util.Optional[holdingv1.Lock]): Boolean =
      optLock.toScala.forall(lock => lock.expiresAt.toScala.exists(t => t.isBefore(now)))
    val senderHoldingCids = listHoldings(participant, sender)
      .collect {
        case (holdingCid, holding)
            if holding.owner == sender.toProtoPrimitive && unlocked(holding.lock) =>
          new holdingv1.Holding.ContractId(holdingCid.contractId)
      }
    val choiceArgs = new transferinstructionv1.TransferFactory_Transfer(
      dsoParty.toProtoPrimitive,
      new transferinstructionv1.Transfer(
        sender.toProtoPrimitive,
        receiver.toProtoPrimitive,
        amount.bigDecimal,
        new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        now,
        now.plus(timeToLife),
        senderHoldingCids.asJava,
        new metadatav1.Metadata(
          description.toList.map(TokenStandardMetadata.reasonMetaKey -> _).toMap.asJava
        ),
      ),
      emptyExtraArgs,
    )
    val scanResponse @ (factory, kind) = sv1ScanBackend.getTransferFactory(choiceArgs)
    aliceValidatorBackend.scanProxy.getTransferFactory(choiceArgs) shouldBe scanResponse
    kind shouldBe expectedKind
    (factory, senderHoldingCids)
  }

  def listHoldings(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        holdingv1.Holding.ContractId,
        holdingv1.HoldingView,
    )
  ] = {
    val holdings =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces = Seq(holdingv1.Holding.TEMPLATE_ID).map(templateId =>
          TemplateId(
            templateId.getPackageId,
            templateId.getModuleName,
            templateId.getEntityName,
          )
        ),
      )
    holdings.map(instr => {
      val instrViewRaw = (instr.event.interfaceViews.head.viewValue
        .getOrElse(throw new RuntimeException("expected an interface view to be present")))
      val instrView = holdingv1.HoldingView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new holdingv1.Holding.ContractId(instr.contractId), instrView)
    })
  }

  def listTransferInstructions(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        transferinstructionv1.TransferInstruction.ContractId,
        transferinstructionv1.TransferInstructionView,
    )
  ] = {
    val instructions =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces =
          Seq(transferinstructionv1.TransferInstruction.TEMPLATE_ID).map(templateId =>
            TemplateId(
              templateId.getPackageId,
              templateId.getModuleName,
              templateId.getEntityName,
            )
          ),
      )
    instructions.map(instr => {
      val instrViewRaw = (instr.event.interfaceViews.head.viewValue
        .getOrElse(throw new RuntimeException("expected an interface view to be present")))
      val instrView = transferinstructionv1.TransferInstructionView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new TransferInstruction.ContractId(instr.contractId), instrView)
    })
  }

  def acceptTransferInstruction(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionAcceptContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionAcceptContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Accept(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def rejectTransferInstruction(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionRejectContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionRejectContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Reject(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def withdrawTransferInstruction(
      participant: ParticipantClientReference,
      receiver: RichPartyId,
      instructionCid: transferinstructionv1.TransferInstruction.ContractId,
      expectedTimeBounds: Option[(CantonTimestamp, CantonTimestamp)] = None,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ) = {
    val choiceContext = sv1ScanBackend.getTransferInstructionWithdrawContext(instructionCid)
    aliceValidatorBackend.scanProxy.getTransferInstructionWithdrawContext(
      instructionCid
    ) shouldBe choiceContext
    participant.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        receiver,
        commands = instructionCid
          .exerciseTransferInstruction_Withdraw(choiceContext.toExtraArgs())
          .commands()
          .asScala
          .toSeq,
        disclosedContracts = choiceContext.disclosedContracts,
        expectedTimeBounds = expectedTimeBounds,
      )
  }

  def createAllocationRequestViaOTCTrade(
      aliceParty: PartyId,
      aliceTransferAmount: BigDecimal,
      bobParty: PartyId,
      bobTransferAmount: BigDecimal,
      venueParty: PartyId,
  )(implicit
      env: SpliceTestConsoleEnvironment
  ): CreateAllocationRequestResult = {
    // Alice creates the TestTradeProposal
    val (_, aliceProposal) =
      actAndCheck(
        "Create test OTC Trade Proposal", {
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
            .submitJava(
              actAs = Seq(aliceParty),
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
        "The new trade proposal exists and is visible to the venue's participant",
        _ => {
          splitwellValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
            .awaitJava(tradingapp.OTCTradeProposal.COMPANION)(
              venueParty,
              predicate = c => c.data.approvers.size == 2,
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
              val requests = listAllocationRequests(aliceWalletClient)
              val request = requests.loneElement
              request.transferLegs.asScala should have size (2)
              request
            }
            val bobRequest = clue("Bob sees the allocation request") {
              val requests = listAllocationRequests(aliceWalletClient)
              val request = requests.loneElement
              request.transferLegs.asScala should have size (2)
              request
            }
            (trade, aliceRequest, bobRequest)
          },
      )

    CreateAllocationRequestResult(trade, aliceRequest, bobRequest)
  }

  def listAllocationRequests(
      walletClient: WalletAppClientReference
  ): Seq[AllocationRequestView] = {
    clue(s"Retrieves allocation requests for ${walletClient.name}") {
      walletClient.listAllocationRequests().map(_.payload)
    }
  }

  def mkTestTradeProposal(
      dso: PartyId,
      venue: PartyId,
      alice: PartyId,
      aliceTransferAmount: BigDecimal,
      bob: PartyId,
      bobTransferAmount: BigDecimal,
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
      new metadatav1.Metadata(java.util.Map.of("some_leg_meta", UUID.randomUUID().toString)),
    )
}

object TokenStandardTest {
  case class CreateAllocationRequestResult(
      trade: tradingapp.OTCTrade.Contract,
      aliceRequest: AllocationRequestView,
      bobRequest: AllocationRequestView,
  )
}
