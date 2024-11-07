package org.lfdecentralizedtrust.splice.scan.admin.http

import com.daml.ledger.javaapi.data as javaApi
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  round as roundCodegen,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  LedgerClient,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpApi
import org.lfdecentralizedtrust.splice.http.v0.definitions.TreeEvent.members.CreatedEvent as HttpCreatedEvent
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItem
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItem.members.UpdateHistoryTransaction as HttpUpdateHistoryTx
import org.lfdecentralizedtrust.splice.store.{StoreTest, TreeUpdateWithMigrationId}
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

      val encoded = ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(original)
      val decoded = ProtobufJsonScanHttpEncodings.httpToLapiUpdate(encoded)

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

    val encoded = ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(original)
    val decoded = ProtobufJsonScanHttpEncodings.httpToLapiUpdate(encoded)

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

    val encoded = ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(original)
    val decoded = ProtobufJsonScanHttpEncodings.httpToLapiUpdate(encoded)

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

    val encodedLossless = ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(tree)
    check(encodedLossless)
    val encodedLossy = CompactJsonScanHttpEncodings.lapiToHttpUpdate(tree)
    check(encodedLossy)
  }

  "make tree update consistent across SVs" in {
    // Random input event ids, to check whether the resulting event ids are deterministic.
    val originalEventIds = Random.shuffle(Vector("a", "b", "c", "d", "e", "f"))
    val leftRootId = originalEventIds(0)
    val rightRootId = originalEventIds(1)
    val leftChildId1 = originalEventIds(2)
    val leftChildId2 = originalEventIds(3)
    val rightChildId1 = originalEventIds(4)
    val rightChildId2 = originalEventIds(5)

    val simpleDamlValue = io.circe.Json.obj("record" -> io.circe.Json.obj())

    def mkCreate(eventId: String) = httpApi.TreeEvent.fromCreatedEvent(
      httpApi.CreatedEvent(
        "created_event",
        eventId = eventId,
        contractId = eventId,
        templateId = "a:b:c",
        packageName = "packageName",
        createArguments = simpleDamlValue,
        createdAt = java.time.OffsetDateTime.now(),
        signatories = Vector.empty,
        observers = Vector.empty,
      )
    )

    def mkExercise(eventId: String, childEventIds: Vector[String]) =
      httpApi.TreeEvent.fromExercisedEvent(
        httpApi.ExercisedEvent(
          "exercised_event",
          eventId = eventId,
          contractId = eventId,
          templateId = "a:b:c",
          packageName = "packageName",
          choice = "choice",
          choiceArgument = simpleDamlValue,
          childEventIds = childEventIds,
          exerciseResult = simpleDamlValue,
          consuming = false,
          actingParties = Vector.empty,
          interfaceId = None,
        )
      )

    // A transaction with two root exercised events, each with two child created events:
    //          [leftRoot,               rightRoot]
    //          /       \                /        \
    // [leftChild1, leftChild2] [rightChild1, rightChild2]
    val original = ProtobufJsonScanHttpEncodings.httpToLapiUpdate(
      httpApi.UpdateHistoryTransaction(
        updateId = "updateId",
        migrationId = 0L,
        workflowId = "workflowId",
        recordTime = "2024-06-03T15:43:38.124Z",
        synchronizerId = "a::b",
        effectiveAt = "2024-06-03T15:43:38.124Z",
        offset = "offset",
        rootEventIds = Vector(leftRootId, rightRootId),
        eventsById = Map(
          leftRootId -> mkExercise(
            leftRootId,
            Vector(leftChildId1, leftChildId2),
          ),
          rightRootId -> mkExercise(
            rightRootId,
            Vector(rightChildId1, rightChildId2),
          ),
          leftChildId1 -> mkCreate(leftChildId1),
          leftChildId2 -> mkCreate(leftChildId2),
          rightChildId1 -> mkCreate(rightChildId1),
          rightChildId2 -> mkCreate(rightChildId2),
        ),
      )
    )

    val withoutLocalData = ScanHttpEncodings
      .makeConsistentAcrossSvs(original)
      .update
      .update
      .asInstanceOf[TransactionTreeUpdate]
      .tree

    val newLeftRoot = withoutLocalData.getEventsById
      .get(withoutLocalData.getRootEventIds.get(0))
      .asInstanceOf[javaApi.ExercisedEvent]
    val newRightRoot = withoutLocalData.getEventsById
      .get(withoutLocalData.getRootEventIds.get(1))
      .asInstanceOf[javaApi.ExercisedEvent]
    val newLeftChild1 = withoutLocalData.getEventsById
      .get(newLeftRoot.getChildEventIds.get(0))
      .asInstanceOf[javaApi.CreatedEvent]
    val newLeftChild2 = withoutLocalData.getEventsById
      .get(newLeftRoot.getChildEventIds.get(1))
      .asInstanceOf[javaApi.CreatedEvent]
    val newRightChild1 = withoutLocalData.getEventsById
      .get(newRightRoot.getChildEventIds.get(0))
      .asInstanceOf[javaApi.CreatedEvent]
    val newRightChild2 = withoutLocalData.getEventsById
      .get(newRightRoot.getChildEventIds.get(1))
      .asInstanceOf[javaApi.CreatedEvent]

    // In the above transaction, contract ids are always equal to event ids
    // These checks make sure that "newLeftRoot" really refers to the left root event
    newLeftRoot.getContractId should be(leftRootId)
    newLeftChild1.getContractId should be(leftChildId1)
    newLeftChild2.getContractId should be(leftChildId2)
    newRightRoot.getContractId should be(rightRootId)
    newRightChild1.getContractId should be(rightChildId1)
    newRightChild2.getContractId should be(rightChildId2)

    // Event ids must be stable, be careful when changing these values
    newLeftRoot.getEventId should be("updateId:0")
    newLeftChild1.getEventId should be("updateId:1")
    newLeftChild2.getEventId should be("updateId:2")
    newRightRoot.getEventId should be("updateId:3")
    newRightChild1.getEventId should be("updateId:4")
    newRightChild2.getEventId should be("updateId:5")

    // makeConsistentAcrossSvs() should be idempotent
    val withoutLocalData2 = ScanHttpEncodings
      .makeConsistentAcrossSvs(original)
      .update
      .update
      .asInstanceOf[TransactionTreeUpdate]
      .tree
    withoutLocalData2 should be(withoutLocalData)
  }
}
