package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1.InstrumentId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.transferinstructionv1.TransferInstruction
import org.lfdecentralizedtrust.splice.http.v0.definitions.TransferInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.IntegrationTestWithSharedEnvironment
import org.lfdecentralizedtrust.splice.util.{
  ChoiceContextWithDisclosures,
  TriggerTestUtil,
  WalletTestUtil,
}

import java.time.Instant
import java.util.{Optional, UUID}

class TokenStandardFetchFallbackIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TriggerTestUtil
    with HasActorSystem
    with HasExecutionContext {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition.simpleTopology1Sv(this.getClass.getSimpleName)
  }

  "Token Standard" should {

    "TransferInstruction context can be fetched from Scan even if it's not yet ingested into the store" in {
      implicit env =>
        pauseScanIngestionWithin(sv1ScanBackend) {
          onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
          val bobUserParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
          aliceWalletClient.tap(100)

          val response = actAndCheck(
            "Alice creates transfer offer",
            aliceWalletClient.createTokenStandardTransfer(
              bobUserParty,
              10,
              s"Transfer",
              CantonTimestamp.now().plusSeconds(3600L),
              UUID.randomUUID().toString,
            ),
          )(
            "Alice and Bob see it",
            _ => {
              Seq(aliceWalletClient, bobWalletClient).foreach(
                _.listTokenStandardTransfers() should have size 1
              )
            },
          )._1

          val cid = response.output match {
            case members.TransferInstructionPending(value) =>
              new TransferInstruction.ContractId(value.transferInstructionCid)
            case _ => fail("The transfers were expected to be pending.")
          }

          clue("SV-1's Scan sees it (still, even though ingestion is paused)") {
            eventuallySucceeds() {
              sv1ScanBackend.getTransferInstructionAcceptContext(cid)
            }
          }
        }
    }

    "AmuletAllocations context can be fetched from Scan even if it's not yet ingested into the store" in {
      implicit env =>
        pauseScanIngestionWithin(sv1ScanBackend) {
          val aliceParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
          val bobParty = onboardWalletUser(bobWalletClient, bobValidatorBackend)
//        // Allocate venue on separate participant node, we still go through the validator API instead of parties.enable
//        // so we can use the standard wallet client APIs but give the party a more useful name than splitwell.
//        val venuePartyHint = s"venue-party-${Random.nextInt()}"
//        val venueParty = splitwellValidatorBackend.onboardUser(
//          splitwellWalletClient.config.ledgerApiUser,
//          Some(
//            PartyId.tryFromProtoPrimitive(
//              s"$venuePartyHint::${splitwellValidatorBackend.participantClient.id.namespace.toProtoPrimitive}"
//            )
//          ),
//        )

          aliceWalletClient.tap(100)
          val referenceId = UUID.randomUUID().toString

          val (_, allocation) = actAndCheck(
            "Alice creates an Allocation",
            aliceWalletClient.allocateAmulet(
              new AllocationSpecification(
                new SettlementInfo(
                  dsoParty.toProtoPrimitive,
                  new Reference(referenceId, Optional.empty),
                  Instant.now,
                  Instant.now.plusSeconds(3600L),
                  Instant.now.plusSeconds(2 * 3600L),
                  ChoiceContextWithDisclosures.emptyMetadata,
                ),
                UUID.randomUUID().toString,
                new TransferLeg(
                  aliceParty.toProtoPrimitive,
                  bobParty.toProtoPrimitive,
                  BigDecimal(10).bigDecimal,
                  new InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
                  ChoiceContextWithDisclosures.emptyMetadata,
                ),
              )
            ),
          )(
            "Alice sees the Allocation",
            _ => {
              val allocation =
                aliceWalletClient
                  .listAmuletAllocations()
                  .loneElement
              allocation.payload.allocation.settlement.settlementRef.id should be(referenceId)
              allocation
            },
          )

          clue("SV-1's Scan sees it (still, even though ingestion is paused)") {
            eventuallySucceeds() {
              sv1ScanBackend.getAllocationTransferContext(
                allocation.contractId.toInterface(Allocation.INTERFACE)
              )
            }
          }
        }
    }

  }

}
