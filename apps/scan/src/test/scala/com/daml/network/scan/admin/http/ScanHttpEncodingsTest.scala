package com.daml.network.scan.admin.http

import com.daml.network.codegen.java.splice.types.Round
import com.daml.network.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  round as roundCodegen,
}
import com.daml.network.environment.ledger.api.{
  LedgerClient,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import com.daml.network.store.{StoreTest, TreeUpdateWithMigrationId}
import com.digitalasset.canton.TestEssentials
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.DomainId
import org.scalatest.matchers.should.Matchers

class ScanHttpEncodingsTest extends StoreTest with TestEssentials with Matchers {

  "LosslessScanHttpEncodings" should {
    "handle transaction updates" in {
      val receiver = mkPartyId("receiver")
      val amuletContract = amulet(receiver, 42.0, 13L, 2.0)

      val javaTree = mkExerciseTx(
        offset = "%08d".format(99),
        root = exercisedEvent(
          contractId = validContractId(1),
          templateId = amuletrulesCodegen.AmuletRules.TEMPLATE_ID,
          interfaceId = Some(amuletCodegen.Amulet.TEMPLATE_ID),
          choice = amuletrulesCodegen.AmuletRules.CHOICE_AmuletRules_Mint.name,
          consuming = false,
          argument = new amuletrulesCodegen.AmuletRules_Mint(
            receiver.toProtoPrimitive,
            amuletContract.payload.amount.initialAmount,
            new roundCodegen.OpenMiningRound.ContractId(validContractId(2)),
          ).toValue,
          new amuletrulesCodegen.AmuletRules_MintResult(
            new amuletCodegen.AmuletCreateSummary[amuletCodegen.Amulet.ContractId](
              amuletContract.contractId,
              new java.math.BigDecimal(42.0),
              new Round(13L),
            )
          ).toValue,
        ),
        Seq(toCreatedEvent(amuletContract, Seq(receiver))),
        dummyDomain,
      )

      val original = TreeUpdateWithMigrationId(
        update = LedgerClient.GetTreeUpdatesResponse(
          update = TransactionTreeUpdate(javaTree),
          domainId = dummyDomain,
        ),
        migrationId = 42L,
      )

      val encoded = LosslessScanHttpEncodings.lapiToHttpUpdate(original)
      val decoded = LosslessScanHttpEncodings.httpToLapiUpdate(encoded)

      decoded shouldBe original
    }
  }

  "handle assignment updates" in {
    val receiver = mkPartyId("receiver")
    val sourceDomain = DomainId.tryFromString("dummy::source")
    val targetDomain = DomainId.tryFromString("dummy::target")
    val amuletContract = amulet(receiver, 42.0, 13L, 2.0)

    val lapiAssignment = mkReassignment(
      offset = "%08d".format(98),
      event = ReassignmentEvent.Assign(
        unassignId = "unassignId",
        submitter = receiver,
        source = sourceDomain,
        target = targetDomain,
        createdEvent = toCreatedEvent(amuletContract, Seq(dsoParty)),
        counter = 71L,
      ),
      recordTime = CantonTimestamp.now(),
    )

    val original = TreeUpdateWithMigrationId(
      update = LedgerClient.GetTreeUpdatesResponse(
        update = ReassignmentUpdate(lapiAssignment),
        domainId = targetDomain,
      ),
      migrationId = 42L,
    )

    val encoded = LosslessScanHttpEncodings.lapiToHttpUpdate(original)
    val decoded = LosslessScanHttpEncodings.httpToLapiUpdate(encoded)

    decoded shouldBe original
  }

  "handle unassignment updates" in {
    val receiver = mkPartyId("receiver")
    val sourceDomain = DomainId.tryFromString("dummy::source")
    val targetDomain = DomainId.tryFromString("dummy::target")
    val amuletContract = amulet(receiver, 42.0, 13L, 2.0)

    val lapiAssignment = mkReassignment(
      offset = "%08d".format(97),
      event = ReassignmentEvent.Unassign(
        unassignId = "unassignId",
        contractId = amuletContract.contractId,
        submitter = receiver,
        source = sourceDomain,
        target = targetDomain,
        counter = 71L,
      ),
      recordTime = CantonTimestamp.now(),
    )

    val original = TreeUpdateWithMigrationId(
      update = LedgerClient.GetTreeUpdatesResponse(
        update = ReassignmentUpdate(lapiAssignment),
        domainId = sourceDomain,
      ),
      migrationId = 42L,
    )

    val encoded = LosslessScanHttpEncodings.lapiToHttpUpdate(original)
    val decoded = LosslessScanHttpEncodings.httpToLapiUpdate(encoded)

    decoded shouldBe original
  }

}
