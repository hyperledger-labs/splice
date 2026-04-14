package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv2
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.Metadata
import org.lfdecentralizedtrust.splice.http.v0.definitions.AllocationInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTest,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.*
import org.lfdecentralizedtrust.splice.wallet.admin.api.client.commands.HttpWalletAppClient.TokenStandard

import java.util.Optional

class AmuletAllocationsIntegrationTest
    extends IntegrationTest
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil
    with TokenStandardV2Test {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  private val someMetadata = new Metadata(java.util.Map.of("k1", "v1", "k2", "v2"))

  private def createAllocationV1(sender: PartyId)(implicit
      ev: SpliceTestConsoleEnvironment
  ) = {
    val validatorPartyId = aliceValidatorBackend.getValidatorPartyId()
    val receiver = validatorPartyId
    val now = CantonTimestamp.now()
    val allocateBefore = now.plusSeconds(3600)
    val settleBefore = now.plusSeconds(3600 * 2)
    def wantedAllocationV1(requestedAt: CantonTimestamp) = new allocationv1.AllocationSpecification(
      new allocationv1.SettlementInfo(
        validatorPartyId.toProtoPrimitive,
        new allocationv1.Reference("some_reference", Optional.empty),
        requestedAt.toInstant,
        allocateBefore.toInstant,
        settleBefore.toInstant,
        someMetadata,
      ),
      "some_transfer_leg_id",
      new allocationv1.TransferLeg(
        sender.toProtoPrimitive,
        receiver.toProtoPrimitive,
        BigDecimal(12).bigDecimal.setScale(10),
        new holdingv1.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        someMetadata,
      ),
    )

    val specification = wantedAllocationV1(now)
    specification -> aliceWalletClient.allocateAmulet(specification)
  }

  private def createAllocationV2(sender: PartyId)(implicit
      ev: SpliceTestConsoleEnvironment
  ) = {
    val validatorPartyId = aliceValidatorBackend.getValidatorPartyId()
    val receiver = validatorPartyId
    val now = CantonTimestamp.now()
    val allocateBefore = now.plusSeconds(3600)
    val settlementDeadline = now.plusSeconds(3600 * 2)

    def wantedAllocationV2(requestedAt: CantonTimestamp) = new allocationv2.AllocationSpecification(
      new allocationv2.SettlementInfo(
        java.util.List.of(validatorPartyId.toProtoPrimitive),
        new allocationv2.Reference("some_reference", Optional.empty),
        requestedAt.toInstant,
        allocateBefore.toInstant,
        java.util.Optional.of(settlementDeadline.toInstant),
        someMetadata,
      ),
      java.util.List.of(
        new allocationv2.TransferLeg(
          "some_transfer_leg",
          basicAccount(sender),
          basicAccount(receiver),
          BigDecimal(12).bigDecimal.setScale(10),
          new holdingv2.InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
          someMetadata,
        )
      ),
      basicAccount(sender),
    )

    val specification = wantedAllocationV2(now)
    specification -> aliceWalletClient.allocateAmulet(specification)
  }

  "A wallet" should {

    "create a token standard amulet allocation" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(1000)

      val (v1Spec, v1Cid) = clue("Create Allocation V1") {
        val (v1Spec, v1Response) = createAllocationV1(aliceUserParty)
        val v1Cid = v1Response.output match {
          case members.AllocationInstructionResultCompleted(completed) => completed.allocationCid
          case _ => fail("Expected allocation v1 to complete")
        }
        (v1Spec, v1Cid)
      }

      val (v2Spec, v2Cid) = clue("Create Allocation V2") {
        val (v2Spec, v2Response) = createAllocationV2(aliceUserParty)
        val v2Cid = v2Response.output match {
          case members.AllocationInstructionResultCompleted(completed) => completed.allocationCid
          case _ => fail("Expected allocation v2 to complete")
        }
        (v2Spec, v2Cid)
      }

      clue("The allocations can be listed") {
        eventually() {
          val allocations = aliceWalletClient.listAmuletAllocations()

          inside(allocations.toList) {
            case TokenStandard.V1AmuletAllocation(v1) ::
                TokenStandard.V2AmuletAllocation(v2) ::
                Nil =>
              v1.contractId.contractId should be(v1Cid)
              v2.contractId.contractId should be(v2Cid)

              v1.payload.allocation should be(v1Spec)
              v2.payload.allocation should be(v2Spec)
          }
        }
      }
    }

  }
}
