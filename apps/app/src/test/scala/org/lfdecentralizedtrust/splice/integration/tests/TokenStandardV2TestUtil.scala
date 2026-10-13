package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2
import com.daml.ledger.javaapi
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.Metadata
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationv2,
  holdingv2,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingappv2
import org.lfdecentralizedtrust.splice.console.ParticipantClientReference
import org.lfdecentralizedtrust.splice.http.v0.definitions.EventHistoryItem
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardV2AllocationIntegrationTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import java.time.Instant
import java.util.{Optional, UUID}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

object TokenStandardV2TestUtil {

  final case class ExpectedTrafficCost(
      action: String,
      lastMeasured: Long,
      tolerancePercent: Int = 5,
  ) {
    def lowerBound: Long = Math.floorDiv(lastMeasured * (100 - tolerancePercent), 100)
    def upperBound: Long = Math.floorDiv(lastMeasured * (100 + tolerancePercent), 100)
    def isWithinBounds(measured: Long): Boolean =
      measured >= lowerBound && measured <= upperBound
  }

}

trait TokenStandardV2TestUtil extends TestCommon {

  import TokenStandardV2TestUtil.ExpectedTrafficCost

  protected val amuletInstrumentIdName = "Amulet"

  protected val tokenStandardV2TestDarPath =
    "token-standard/examples/splice-token-test-trading-app-v2/.daml/dist/splice-token-test-trading-app-v2-current.dar"

  val emptyMetadata = new metadatav1.Metadata(java.util.Map.of())
  val emptyChoiceContext = new metadatav1.ChoiceContext(java.util.Map.of())

  def basicAccount(party: PartyId): holdingv2.Account =
    new holdingv2.Account(
      java.util.Optional.of(party.toProtoPrimitive),
      java.util.Optional.empty(),
      "",
    )

  private def trafficCostReport(
      measurements: Seq[(ExpectedTrafficCost, Long)]
  ): String = {
    val actionMaxLength = measurements.map(_._1.action.length).maxOption.getOrElse(0)
    val rows = measurements.map { case (expected, measured) =>
      val delta =
        if (expected.lastMeasured == 0) "n/a"
        else
          f"${(measured - expected.lastMeasured) * 100.0 / expected.lastMeasured}%+.2f%%"
      val verdict = if (expected.isWithinBounds(measured)) "ok" else "out of bounds"
      s"  ${expected.action.padTo(actionMaxLength, ' ')}  expected=${expected.lastMeasured}  " +
        s"measured=$measured  delta=$delta  " +
        s"bounds=[${expected.lowerBound}, ${expected.upperBound}]  $verdict"
    }
    val newExpectations = measurements.map { case (expected, measured) =>
      s"""    ExpectedTrafficCost("${expected.action}", $measured),"""
    }
    (Seq("Traffic cost check per action, in bytes:") ++ rows ++
      Seq("New expected costs matching this run:") ++ newExpectations).mkString("\n")
  }

  def checkTrafficCosts(
      actions: Seq[(String, ExpectedTrafficCost)]
  )(implicit env: SpliceTestConsoleEnvironment): Seq[(String, EventHistoryItem)] = {
    val events = actions.map { case (updateId, expected) =>
      expected -> clue(s"Checking traffic & activity records for '${expected.action}'") {
        eventually() {
          inside(sv1ScanBackend.getEventById(updateId, None)) {
            case Some(
                  item @ EventHistoryItem(
                    _,
                    Some(_),
                    Some(_),
                    _,
                  )
                ) =>
              item
          }
        }
      }
    }

    val measurements = events.map { case (expected, item) =>
      expected -> item.trafficSummary
        .getOrElse(fail(s"No traffic summary for '${expected.action}'"))
        .totalTrafficCost
    }
    logger.info(trafficCostReport(measurements))

    val drifted = measurements.filterNot { case (expected, measured) =>
      expected.isWithinBounds(measured)
    }
    if (drifted.nonEmpty) {
      fail(
        s"Traffic cost drifted for ${drifted.map(_._1.action).mkString(", ")}\n" +
          trafficCostReport(measurements)
      )
    }

    events.map { case (expected, item) => expected.action -> item }
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
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
          .submitJava(
            actAs = Seq(venueParty),
            commands = mkTestTrade(
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
      "There exists a trade visible to the venue's participant",
      _ =>
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.acs
          .awaitJava(tradingappv2.OTCTrade.COMPANION)(
            venueParty
          ),
    )

    val (_, (bobAllocationRequest, aliceAllocationRequest)) = actAndCheck(
      "Venue creates allocation requests", {
        bobValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
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
        val bobAllocationRequest = inside(
          bobWalletClient.listAllocationRequests()
        ) {
          case (allocationRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest) +: Nil =>
            allocationRequest
        }
        val aliceAllocationRequest = inside(
          aliceWalletClient.listAllocationRequests()
        ) {
          case (allocationRequest: HttpWalletAppClient.TokenStandard.V2AllocationRequest) +: Nil =>
            allocationRequest
        }

        (bobAllocationRequest, aliceAllocationRequest)
      },
    )

    CreateAllocationRequestResult(otcTrade, aliceAllocationRequest, bobAllocationRequest)
  }

  def mkTestTrade(
      dso: PartyId,
      venue: PartyId,
      alice: PartyId,
      aliceTransferAmount: BigDecimal,
      bob: PartyId,
      bobTransferAmount: BigDecimal,
  ): tradingappv2.OTCTrade = {
    val aliceLeg = new tradingappv2.TradeLeg(
      dso.toProtoPrimitive,
      mkTransferLeg("leg0", alice, bob, aliceTransferAmount),
    )
    // TODO(#561): swap against a token from the token reference implementation
    val bobLeg = new tradingappv2.TradeLeg(
      dso.toProtoPrimitive,
      mkTransferLeg("leg1", bob, alice, bobTransferAmount),
    )
    new tradingappv2.OTCTrade(
      venue.toProtoPrimitive,
      Seq(aliceLeg, bobLeg).asJava,
      Instant.now(),
      // settleAt:
      // - Allocations should be made before this time.
      // Settlement happens at any point after this time.
      Instant.now().plusSeconds(30L),
      java.util.Optional.of(Instant.now().plusSeconds(180L)),
    )
  }

  def mkTransferLeg(
      legId: String,
      sender: PartyId,
      receiver: PartyId,
      amount: BigDecimal,
  ): allocationv2.TransferLeg =
    new allocationv2.TransferLeg(
      legId,
      basicAccount(sender),
      basicAccount(receiver),
      amount.bigDecimal,
      amuletInstrumentIdName,
      new metadatav1.Metadata(java.util.Map.of("some_leg_meta", UUID.randomUUID().toString)),
    )

  def transferLegSideForAuthorizer(
      authorizer: PartyId,
      transferLeg: allocationv2.TransferLeg,
  ): allocationv2.TransferLegSide = {
    val (side, otherside) =
      if (transferLeg.sender.owner.toScala.contains(authorizer.toProtoPrimitive)) {
        allocationv2.TransferSide.SENDERSIDE -> transferLeg.receiver
      } else if (transferLeg.receiver.owner.toScala.contains(authorizer.toProtoPrimitive)) {
        allocationv2.TransferSide.RECEIVERSIDE -> transferLeg.sender
      } else {
        throw new IllegalArgumentException(
          s"Transfer leg `${transferLeg.transferLegId}` does not involve authorizer `${authorizer.toProtoPrimitive}`"
        )
      }

    new allocationv2.TransferLegSide(
      transferLeg.transferLegId,
      side,
      otherside,
      transferLeg.amount,
      transferLeg.instrumentId,
      transferLeg.meta,
    )
  }

  def transferLegsFromTrade(
      otcTrade: tradingappv2.OTCTrade.Contract
  ): Seq[allocationv2.TransferLeg] =
    otcTrade.data.tradeLegs.asScala.map(_.leg).toSeq

  def listHoldingsV2(
      participantClient: ParticipantClientReference,
      party: PartyId,
  ): Seq[
    (
        holdingv2.Holding.ContractId,
        holdingv2.HoldingView,
    )
  ] = {
    val holdings =
      participantClient.ledger_api.state.acs.of_party(
        party = party,
        filterInterfaces = Seq(holdingv2.Holding.TEMPLATE_ID).map(templateId =>
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
      val instrView = holdingv2.HoldingView
        .valueDecoder()
        .decode(
          javaapi.data.DamlRecord.fromProto(
            v2.value.Record.toJavaProto(instrViewRaw)
          )
        )
      (new holdingv2.Holding.ContractId(instr.contractId), instrView)
    })
  }

  def mkSettlementV2(executor: PartyId, settlementRef: String = "some_reference") =
    new allocationv2.SettlementInfo(
      java.util.List.of(executor.toProtoPrimitive),
      settlementRef,
      Optional.empty,
      new Metadata(java.util.Map.of("k1", "v1", "k2", "v2")),
    )
  def mkAllocationSpecV2(
      admin: PartyId,
      sender: PartyId,
      receiver: PartyId,
      legId: String,
      settlementDeadline: Instant,
  ) =
    new allocationv2.AllocationSpecification(
      admin.toProtoPrimitive,
      basicAccount(sender),
      java.util.List.of(
        transferLegSideForAuthorizer(
          sender,
          new allocationv2.TransferLeg(
            legId,
            basicAccount(sender),
            basicAccount(receiver),
            BigDecimal(12).bigDecimal.setScale(10),
            amuletInstrumentIdName,
            new Metadata(java.util.Map.of("k3", "v3")),
          ),
        )
      ),
      java.util.Optional.of(settlementDeadline),
      java.util.Optional.empty[java.util.Map[String, java.math.BigDecimal]](),
      false,
      new Metadata(java.util.Map.of("k4", "v4")),
    )

}
