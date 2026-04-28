package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.{
  allocationv2,
  holdingv2,
  metadatav1,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.testing.apps.tradingappv2
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  SpliceTestConsoleEnvironment,
  TestCommon,
}
import org.lfdecentralizedtrust.splice.integration.tests.TokenStandardV2AllocationIntegrationTest.CreateAllocationRequestResult
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient

import java.time.Instant
import java.util.UUID
import scala.jdk.CollectionConverters.*

trait TokenStandardV2TestUtil extends TestCommon {

  protected val tokenStandardV2TestDarPath =
    "token-standard/examples/splice-token-test-trading-app-v2/.daml/dist/splice-token-test-trading-app-v2-current.dar"

  def basicAccount(party: PartyId): holdingv2.Account =
    new holdingv2.Account(
      party.toProtoPrimitive,
      java.util.Optional.empty(),
      "",
    )

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

}
