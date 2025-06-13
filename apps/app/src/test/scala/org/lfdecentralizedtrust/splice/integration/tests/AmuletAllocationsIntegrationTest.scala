package org.lfdecentralizedtrust.splice.integration.tests

import com.daml.ledger.api.v2.event.CreatedEvent.toJavaProto
import com.daml.ledger.javaapi.data.CreatedEvent
import com.digitalasset.canton.HasExecutionContext
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletallocation.AmuletAllocation
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.allocationv1.{
  AllocationSpecification,
  SettlementInfo,
  TransferLeg,
  Reference as SettlementReference,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv1.InstrumentId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.metadatav1.Metadata
import org.lfdecentralizedtrust.splice.http.v0.definitions.AllocationInstructionResultOutput.members
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.*

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.util.Optional

class AmuletAllocationsIntegrationTest
    extends IntegrationTestWithSharedEnvironment
    with HasExecutionContext
    with WalletTestUtil
    with WalletTxLogTestUtil {

  override def environmentDefinition: EnvironmentDefinition = {
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
  }

  private def createAllocation(sender: PartyId)(implicit
      ev: SpliceTestConsoleEnvironment
  ) = {
    val validatorPartyId = aliceValidatorBackend.getValidatorPartyId()
    val receiver = validatorPartyId
    val now = LocalDateTime
      .now()
      .plusSeconds(3600)
      // daml precision
      .truncatedTo(ChronoUnit.MICROS)
      .toInstant(ZoneOffset.UTC)
    val settleAndAllocateBefore = now.plusSeconds(3600)
    def wantedAllocation(requestedAt: Instant) = new AllocationSpecification(
      new SettlementInfo(
        validatorPartyId.toProtoPrimitive,
        new SettlementReference("some_reference", Optional.empty),
        requestedAt,
        settleAndAllocateBefore,
        settleAndAllocateBefore,
        new Metadata(java.util.Map.of("k1", "v1", "k2", "v2")),
      ),
      "some_transfer_leg_id",
      new TransferLeg(
        sender.toProtoPrimitive,
        receiver.toProtoPrimitive,
        BigDecimal(12).bigDecimal.setScale(10),
        new InstrumentId(dsoParty.toProtoPrimitive, "Amulet"),
        new Metadata(java.util.Map.of("k3", "v3")),
      ),
    )

    actAndCheck(
      "create an allocation", {
        aliceWalletClient.allocateAmulet(wantedAllocation(now))
      },
    )(
      "the allocation is created",
      created => {
        inside(created.output) { case members.AllocationInstructionResultCompleted(_) =>
          succeed
        }

        // TODO (#1106): use whatever endpoint was introduced to check
        val allocation =
          aliceValidatorBackend.participantClientWithAdminToken.ledger_api.state.acs
            .of_party(
              party = sender,
              filterTemplates = Seq(AmuletAllocation.TEMPLATE_ID).map(TemplateId.fromJavaIdentifier),
            )
            .loneElement

        val specification = Contract
          .fromCreatedEvent(AmuletAllocation.COMPANION)(
            CreatedEvent.fromProto(toJavaProto(allocation.event))
          )
          .getOrElse(fail(s"Failed to parse allocation contract: $allocation"))
          .payload
          .allocation

        specification should be(wantedAllocation(specification.settlement.requestedAt))
      },
    )
  }

  "A wallet" should {

    "create a token standard amulet allocation" in { implicit env =>
      val aliceUserParty = onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      aliceWalletClient.tap(1000)

      createAllocation(aliceUserParty)
    }

  }
}
