package org.lfdecentralizedtrust.splice.scan.admin.http

import com.daml.ledger.javaapi.data as javaApi
import com.digitalasset.canton.TestEssentials
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import org.lfdecentralizedtrust.splice.codegen.java.splice.types.Round
import org.lfdecentralizedtrust.splice.codegen.java.splice.{
  amulet as amuletCodegen,
  amuletrules as amuletrulesCodegen,
  round as roundCodegen,
}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions as httpApi
import org.lfdecentralizedtrust.splice.http.v0.definitions.TreeEvent.members.CreatedEvent as HttpCreatedEvent
import org.lfdecentralizedtrust.splice.http.v0.definitions.UpdateHistoryItem.members.{
  UpdateHistoryReassignment,
  UpdateHistoryTransaction as HttpUpdateHistoryTx,
}
import org.lfdecentralizedtrust.splice.http.v0.definitions.{DamlValueEncoding, UpdateHistoryItem}
import org.lfdecentralizedtrust.splice.store.{StoreTestBase, TreeUpdateWithMigrationId}
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanVerdictStore.VerdictResultDbValue
import org.lfdecentralizedtrust.splice.util.EventId
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.util.Random

class ScanHttpEncodingsTest extends StoreTestBase with TestEssentials with Matchers {
  private val haveMicrosecondPrecision = endWith regex """\.\d{6}Z"""

  "LosslessScanHttpEncodings" should {
    "handle transaction updates" in {
      val receiver = mkPartyId("receiver")
      val amuletContract = amulet(receiver, 42.0, 13L, 2.0)

      val javaTree = mkExerciseTx(
        offset = 99,
        root = exercisedEvent(
          contractId = validContractId(1),
          templateId = amuletrulesCodegen.AmuletRules.TEMPLATE_ID_WITH_PACKAGE_ID,
          interfaceId = Some(amuletCodegen.Amulet.TEMPLATE_ID_WITH_PACKAGE_ID),
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
        update = UpdateHistoryResponse(
          update = TransactionTreeUpdate(javaTree),
          synchronizerId = dummyDomain,
        ),
        migrationId = 42L,
      )

      val encoded =
        ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(
          original,
          EventId.prefixedFromUpdateIdAndNodeId,
        )
      encoded match {
        case HttpUpdateHistoryTx(tx) => tx.recordTime should haveMicrosecondPrecision
        case _ => fail("Expected UpdateHistoryTransaction")
      }
      val decoded = ProtobufJsonScanHttpEncodings.httpToLapiUpdate(encoded)

      decoded shouldBe original
    }
  }

  "handle assignment updates" in {
    val receiver = mkPartyId("receiver")
    val sourceDomain = SynchronizerId.tryFromString("dummy::source")
    val targetDomain = SynchronizerId.tryFromString("dummy::target")
    val amuletContract = amulet(receiver, 42.0, 13L, 2.0)

    val lapiAssignment = mkReassignment(
      offset = 98,
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
      update = UpdateHistoryResponse(
        update = ReassignmentUpdate(lapiAssignment),
        synchronizerId = targetDomain,
      ),
      migrationId = 42L,
    )

    val encoded =
      ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(
        original,
        EventId.prefixedFromUpdateIdAndNodeId,
      )
    encoded match {
      case UpdateHistoryReassignment(r) => r.recordTime should haveMicrosecondPrecision
      case _ => fail("Expected UpdateHistoryReassignment")
    }

    val decoded = ProtobufJsonScanHttpEncodings.httpToLapiUpdate(encoded)

    decoded shouldBe original
  }

  "handle unassignment updates" in {
    val receiver = mkPartyId("receiver")
    val sourceDomain = SynchronizerId.tryFromString("dummy::source")
    val targetDomain = SynchronizerId.tryFromString("dummy::target")
    val amuletContract = amulet(receiver, 42.0, 13L, 2.0)

    val lapiAssignment = mkReassignment(
      offset = 97,
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
      update = UpdateHistoryResponse(
        update = ReassignmentUpdate(lapiAssignment),
        synchronizerId = sourceDomain,
      ),
      migrationId = 42L,
    )

    val encoded =
      ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(
        original,
        EventId.prefixedFromUpdateIdAndNodeId,
      )
    encoded match {
      case UpdateHistoryReassignment(r) => r.recordTime should haveMicrosecondPrecision
      case _ => fail("Expected UpdateHistoryReassignment")
    }

    val decoded = ProtobufJsonScanHttpEncodings.httpToLapiUpdate(encoded)

    decoded shouldBe original
  }

  "return observers and signatories sorted" in {
    val signatories = ('a' to 'd').map(c => mkPartyId(c.toString))
    val observers = ('c' to 'f').map(c => mkPartyId(c.toString))
    val tree = TreeUpdateWithMigrationId(
      update = UpdateHistoryResponse(
        update = TransactionTreeUpdate(
          mkCreateTx(
            10,
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
        synchronizerId = dummyDomain,
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

    val encodedLossless =
      ProtobufJsonScanHttpEncodings.lapiToHttpUpdate(tree, EventId.prefixedFromUpdateIdAndNodeId)
    check(encodedLossless)
    val encodedLossy =
      CompactJsonScanHttpEncodings().lapiToHttpUpdate(tree, EventId.prefixedFromUpdateIdAndNodeId)
    check(encodedLossy)
  }

  "make tree update consistent across SVs" in {
    // Random input event ids, to check whether the resulting event ids are deterministic.
    val originalEventIds = Random.shuffle(Vector("a", "b", "c", "d", "e", "f")).zipWithIndex.map {
      case (updateId, index) => s"$updateId:$index"
    }
    val leftRootId = originalEventIds(0)
    val rightRootId = originalEventIds(3)
    val leftChildId1 = originalEventIds(1)
    val leftChildId2 = originalEventIds(2)
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
        offset = "000000000000000001",
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
      .get(withoutLocalData.getRootNodeIds.get(0))
      .asInstanceOf[javaApi.ExercisedEvent]
    val newLeftRootChildNodeIds = withoutLocalData.getChildNodeIds(newLeftRoot)
    val newRightRoot = withoutLocalData.getEventsById
      .get(withoutLocalData.getRootNodeIds.get(1))
      .asInstanceOf[javaApi.ExercisedEvent]
    val newLeftChild1 = withoutLocalData.getEventsById
      .get(newLeftRootChildNodeIds.get(0))
      .asInstanceOf[javaApi.CreatedEvent]
    val newLeftChild2 = withoutLocalData.getEventsById
      .get(newLeftRootChildNodeIds.get(1))
      .asInstanceOf[javaApi.CreatedEvent]
    val newRightRootChildNodeIds = withoutLocalData.getChildNodeIds(newRightRoot)
    val newRightChild1 = withoutLocalData.getEventsById
      .get(newRightRootChildNodeIds.get(0))
      .asInstanceOf[javaApi.CreatedEvent]
    val newRightChild2 = withoutLocalData.getEventsById
      .get(newRightRootChildNodeIds.get(1))
      .asInstanceOf[javaApi.CreatedEvent]

    // In the above transaction, contract ids are always equal to event ids
    // These checks make sure that "newLeftRoot" really refers to the left root event
    newLeftRoot.getContractId should be(leftRootId)
    newLeftChild1.getContractId should be(leftChildId1)
    newLeftChild2.getContractId should be(leftChildId2)
    newRightRoot.getContractId should be(rightRootId)
    newRightChild1.getContractId should be(rightChildId1)
    newRightChild2.getContractId should be(rightChildId2)

    // node ids must be stable, be careful when changing these values
    newLeftRoot.getNodeId should be(0)
    newLeftChild1.getNodeId should be(1)
    newLeftChild2.getNodeId should be(2)
    newRightRoot.getNodeId should be(3)
    newRightChild1.getNodeId should be(4)
    newRightChild2.getNodeId should be(5)

    // makeConsistentAcrossSvs() should be idempotent
    val withoutLocalData2 = ScanHttpEncodings
      .makeConsistentAcrossSvs(original)
      .update
      .update
      .asInstanceOf[TransactionTreeUpdate]
      .tree
    withoutLocalData2 should be(withoutLocalData)
  }

  "return version specific event ids" in {
    val tree = TreeUpdateWithMigrationId(
      update = UpdateHistoryResponse(
        update = TransactionTreeUpdate(
          mkCreateTx(
            10,
            Seq(
              amulet(mkPartyId("Alice"), 42.0, 13L, 2.0)
            ),
            Instant.now(),
            createdEventSignatories = Seq.empty,
            dummyDomain,
            "",
            createdEventObservers = Seq.empty,
          )
        ),
        synchronizerId = dummyDomain,
      ),
      migrationId = 42L,
    )

    inside(
      ScanHttpEncodings.encodeUpdate(tree, DamlValueEncoding.ProtobufJson, ScanHttpEncodings.V0)
    ) { case HttpUpdateHistoryTx(value) =>
      value.rootEventIds should not be empty
      forAll(value.rootEventIds)(eventId => eventId should be(s"#${tree.update.update.updateId}:0"))
    }
    inside(
      ScanHttpEncodings.encodeUpdate(tree, DamlValueEncoding.ProtobufJson, ScanHttpEncodings.V1)
    ) { case HttpUpdateHistoryTx(value) =>
      value.rootEventIds should not be empty
      forAll(value.rootEventIds)(eventId => eventId should be(s"${tree.update.update.updateId}:0"))
    }
  }

  "encode verdict" in {
    import io.circe.Json

    val recordTs = CantonTimestamp.now()

    val partyA = mkPartyId("Alice").toProtoPrimitive
    val partyB = mkPartyId("Bob").toProtoPrimitive
    val partyC = mkPartyId("Charlie").toProtoPrimitive
    val dummyDomainLongString =
      "global-domain::122015405b2293753a19749682fce0c2a6bb6bf03bcd7d9bde2cd0dce9e426c9a2df"
    val dummyDomainLong = SynchronizerId.tryFromString(dummyDomainLongString)
    val verdictBase = DbScanVerdictStore.VerdictT(
      rowId = 0L,
      migrationId = 3L,
      domainId = dummyDomainLong,
      recordTime = recordTs,
      finalizationTime = recordTs,
      submittingParticipantUid = mkParticipantId("participant").toProtoPrimitive,
      verdictResult = VerdictResultDbValue.Unspecified,
      mediatorGroup = 7,
      updateId = "update-xyz",
      submittingParties = Seq(partyA, partyB),
      transactionRootViews = Seq(0, 2),
    )

    val view0 = DbScanVerdictStore.TransactionViewT(
      verdictRowId = 0L,
      viewId = 0,
      informees = Seq(partyC),
      confirmingParties = Json.arr(
        Json.obj(
          "parties" -> Json.arr(Json.fromString(partyA)),
          "threshold" -> Json.fromInt(1),
        )
      ),
      subViews = Seq(3),
      viewHash = Some("hash0"),
    )
    val view1 = DbScanVerdictStore.TransactionViewT(
      verdictRowId = 0L,
      viewId = 1,
      informees = Seq(partyB),
      confirmingParties = Json.arr(
        Json.obj(
          "parties" -> Json.arr(Json.fromString(partyB), Json.fromString(partyC)),
          "threshold" -> Json.fromInt(2),
        )
      ),
      subViews = Seq.empty,
      viewHash = Some("hash1"),
    )

    val view2 = DbScanVerdictStore.TransactionViewT(
      verdictRowId = 0L,
      viewId = 2,
      informees = Seq(partyA),
      confirmingParties = Json.arr(
        Json.obj(
          "parties" -> Json.arr(Json.fromString(partyA), Json.fromString(partyB)),
          "threshold" -> Json.fromInt(2),
        ),
        Json.obj(
          "parties" -> Json.arr(Json.fromString(partyC)),
          "threshold" -> Json.fromInt(1),
        ),
      ),
      subViews = Seq(1),
      viewHash = Some("hash2"),
    )

    val view3 = DbScanVerdictStore.TransactionViewT(
      verdictRowId = 0L,
      viewId = 3,
      informees = Seq(partyA, partyB),
      confirmingParties = Json.arr(
        Json.obj(
          "parties" -> Json.arr(Json.fromString(partyA), Json.fromString(partyC)),
          "threshold" -> Json.fromInt(2),
        )
      ),
      subViews = Seq(4, 5),
      viewHash = Some("hash3"),
    )

    val view4 = DbScanVerdictStore.TransactionViewT(
      verdictRowId = 0L,
      viewId = 4,
      informees = Seq(partyB),
      confirmingParties = Json.arr(
        Json.obj(
          "parties" -> Json.arr(Json.fromString(partyB)),
          "threshold" -> Json.fromInt(1),
        )
      ),
      subViews = Seq(6),
      viewHash = Some("hash4"),
    )
    val view5 = DbScanVerdictStore.TransactionViewT(
      verdictRowId = 0L,
      viewId = 5,
      informees = Seq(partyC),
      confirmingParties = Json.arr(
        Json.obj(
          "parties" -> Json.arr(
            Json.fromString(partyA),
            Json.fromString(partyB),
            Json.fromString(partyC),
          ),
          "threshold" -> Json.fromInt(3),
        )
      ),
      subViews = Seq.empty,
      viewHash = Some("hash5"),
    )

    val view6 = DbScanVerdictStore.TransactionViewT(
      verdictRowId = 0L,
      viewId = 6,
      informees = Seq(partyA),
      confirmingParties = Json.arr(
        Json.obj(
          "parties" -> Json.arr(Json.fromString(partyA)),
          "threshold" -> Json.fromInt(1),
        )
      ),
      subViews = Seq.empty,
      viewHash = None, // Test that None encodes to empty string
    )

    val viewsIn = Seq(view2, view0, view1, view6, view4, view5, view3)

    val encodedVerdict = ScanHttpEncodings.encodeVerdict(verdictBase, viewsIn)

    encodedVerdict.updateId shouldBe verdictBase.updateId
    encodedVerdict.migrationId shouldBe verdictBase.migrationId
    encodedVerdict.domainId shouldBe dummyDomainLongString
    encodedVerdict.recordTime should haveMicrosecondPrecision
    encodedVerdict.finalizationTime should haveMicrosecondPrecision
    encodedVerdict.submittingParties shouldBe verdictBase.submittingParties.toVector
    encodedVerdict.submittingParticipantUid shouldBe verdictBase.submittingParticipantUid
    encodedVerdict.mediatorGroup shouldBe verdictBase.mediatorGroup
    encodedVerdict.verdictResult shouldBe httpApi.VerdictResult.VerdictResultUnspecified

    encodedVerdict.transactionViews.rootViews shouldBe verdictBase.transactionRootViews.toVector

    val views = encodedVerdict.transactionViews.views
    views.map(_.viewId) shouldBe Vector(0, 1, 2, 3, 4, 5, 6)

    inside(views(0)) { case v =>
      v.viewId shouldBe 0
      v.informees shouldBe Vector(partyC)
      v.subViews shouldBe Vector(3)
      v.confirmingParties shouldBe Vector(httpApi.Quorum(Vector(partyA), 1))
      v.viewHash shouldBe "hash0" // Some("hash0") encodes to "hash0"
    }

    inside(views(1)) { case v =>
      v.viewId shouldBe 1
      v.informees shouldBe Vector(partyB)
      v.subViews shouldBe Vector.empty
      v.confirmingParties shouldBe Vector(
        httpApi.Quorum(Vector(partyB, partyC), 2)
      )
    }

    inside(views(2)) { case v =>
      v.viewId shouldBe 2
      v.informees shouldBe Vector(partyA)
      v.subViews shouldBe Vector(1)
      v.confirmingParties shouldBe Vector(
        httpApi.Quorum(Vector(partyA, partyB), 2),
        httpApi.Quorum(Vector(partyC), 1),
      )
    }

    inside(views(3)) { case v =>
      v.viewId shouldBe 3
      v.informees shouldBe Vector(partyA, partyB)
      v.subViews shouldBe Vector(4, 5)
      v.confirmingParties shouldBe Vector(
        httpApi.Quorum(Vector(partyA, partyC), 2)
      )
    }

    inside(views(4)) { case v =>
      v.viewId shouldBe 4
      v.informees shouldBe Vector(partyB)
      v.subViews shouldBe Vector(6)
      v.confirmingParties shouldBe Vector(
        httpApi.Quorum(Vector(partyB), 1)
      )
    }

    inside(views(5)) { case v =>
      v.viewId shouldBe 5
      v.informees shouldBe Vector(partyC)
      v.subViews shouldBe Vector.empty
      v.confirmingParties shouldBe Vector(
        httpApi.Quorum(Vector(partyA, partyB, partyC), 3)
      )
    }

    inside(views(6)) { case v =>
      v.viewId shouldBe 6
      v.informees shouldBe Vector(partyA)
      v.subViews shouldBe Vector.empty
      v.confirmingParties shouldBe Vector(
        httpApi.Quorum(Vector(partyA), 1)
      )
      v.viewHash shouldBe "" // None encodes to empty string
    }

    val encodedAccepted =
      ScanHttpEncodings.encodeVerdict(
        verdictBase.copy(verdictResult = VerdictResultDbValue.Accepted),
        viewsIn,
      )
    encodedAccepted.verdictResult shouldBe httpApi.VerdictResult.VerdictResultAccepted

    val encodedRejected =
      ScanHttpEncodings.encodeVerdict(
        verdictBase.copy(verdictResult = VerdictResultDbValue.Rejected),
        viewsIn,
      )
    encodedRejected.verdictResult shouldBe httpApi.VerdictResult.VerdictResultRejected
  }
}
