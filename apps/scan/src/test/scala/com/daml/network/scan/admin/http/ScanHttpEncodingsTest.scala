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
import com.daml.network.http.v0.definitions.TreeEvent.members.CreatedEvent as HttpCreatedEvent
import com.daml.network.http.v0.definitions.UpdateHistoryItem
import com.daml.network.http.v0.definitions.UpdateHistoryItem.members.UpdateHistoryTransaction as HttpUpdateHistoryTx
import com.daml.network.store.{StoreTest, TreeUpdateWithMigrationId}
import com.digitalasset.canton.TestEssentials
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.DomainId
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Random

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

  "return observers and signatories sorted" in {
    val signatories = ('a' to 'd').map(c => mkPartyId(c.toString))
    val observers = ('c' to 'f').map(c => mkPartyId(c.toString))
    val tree = TreeUpdateWithMigrationId(
      update = LedgerClient.GetTreeUpdatesResponse(
        update = TransactionTreeUpdate(
          mkCreateTx(
            "000a",
            Seq(
              amulet(mkPartyId("Alice"), 42.0, 13L, 2.0)
            ),
            Instant.now(),
            createdEventSignatories = Random.shuffle(signatories),
            dummyDomain,
            "",
            createdEventObservers = Random.shuffle(observers),
          )
        ),
        domainId = dummyDomain,
      ),
      migrationId = 42L,
    )

    def check(item: UpdateHistoryItem) = {
      inside(item) { case HttpUpdateHistoryTx(tx) =>
        inside(tx.eventsById(tx.rootEventIds.loneElement)) { case HttpCreatedEvent(value) =>
          value.signatories should be(signatories.map(_.toProtoPrimitive))
          value.observers should be(observers.map(_.toProtoPrimitive))
        }
      }
    }

    val encodedLossless = LosslessScanHttpEncodings.lapiToHttpUpdate(tree)
    check(encodedLossless)
    val encodedLossy = LossyScanHttpEncodings.lapiToHttpUpdate(tree)
    check(encodedLossy)
  }

}
