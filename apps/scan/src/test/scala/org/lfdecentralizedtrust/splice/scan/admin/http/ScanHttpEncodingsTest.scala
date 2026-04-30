package org.lfdecentralizedtrust.splice.scan.admin.http

import com.daml.ledger.javaapi.data as javaApi
import com.digitalasset.canton.TestEssentials
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.util.HexString
import com.google.protobuf.ByteString
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
import org.lfdecentralizedtrust.splice.http.OmitNullString
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
import scala.jdk.CollectionConverters.*
import scala.util.Random

class ScanHttpEncodingsTest extends StoreTestBase with TestEssentials with Matchers {
  private val haveMicrosecondPrecision = endWith regex """\.\d{6}Z"""

  "LosslessScanHttpEncodings" should {
    "handle transaction updates" in {
      val receiver = mkPartyId("receiver")
      val amuletContract = amulet(receiver, 42.0, 13L, 2.0)

      val extTxnHashHexString = "4d68f590e4a298d9617ebe07b98c6ecbe04b7f3d7a5327f0e0ad4719638302b7"
      val externalTxnHash =
        HexString.parseToByteString(extTxnHashHexString).getOrElse(ByteString.EMPTY)

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
        externalTransactionHash = externalTxnHash,
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
        externalTransactionHash =
          Some(OmitNullString("4d68f590e4a298d9617ebe07b98c6ecbe04b7f3d7a5327f0e0ad4719638302b7")),
      )
    )

    val withoutLocalData = ScanHttpEncodings
      .makeConsistentAcrossSvs(original, ExternalHashInclusionPolicy.AlwaysInclude, None)
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
      .makeConsistentAcrossSvs(original, ExternalHashInclusionPolicy.AlwaysInclude, None)
      .update
      .update
      .asInstanceOf[TransactionTreeUpdate]
      .tree
    withoutLocalData2 should be(withoutLocalData)
  }

  "makeConsistentAcrossSvs on a nested Java Transaction" should {
    // Build events directly with given (original) node id and, for exercises,
    // the given last-descendant node id.
    def mkCreatedEvent(nodeId: Int, contractId: String): javaApi.CreatedEvent =
      new javaApi.CreatedEvent(
        /*witnessParties = */ java.util.Collections.emptyList(),
        /*offset = */ 0L,
        /*nodeId = */ nodeId,
        /*templateId = */ new javaApi.Identifier("pkg", "Mod", "Tpl"),
        /*packageName = */ "pkg-name",
        /*contractId = */ contractId,
        /*arguments = */ new javaApi.DamlRecord(
          java.util.Collections.emptyList[javaApi.DamlRecord.Field]()
        ),
        /*createdEventBlob = */ ByteString.EMPTY,
        /*interfaceViews = */ java.util.Collections.emptyMap(),
        /*failedInterfaceViews = */ java.util.Collections.emptyMap(),
        /*contractKey = */ java.util.Optional.empty(),
        /*signatories = */ java.util.Collections.emptyList(),
        /*observers = */ java.util.Collections.emptyList(),
        /*createdAt = */ Instant.EPOCH,
        /*acsDelta = */ false,
        /*representativePackageId = */ "pkg",
      )

    def mkExercisedEvent(
        nodeId: Int,
        lastDescendantNodeId: Int,
        contractId: String,
    ): javaApi.ExercisedEvent =
      new javaApi.ExercisedEvent(
        /*witnessParties = */ java.util.Collections.emptyList(),
        /*offset = */ 0L,
        /*nodeId = */ nodeId,
        /*templateId = */ new javaApi.Identifier("pkg", "Mod", "Tpl"),
        /*packageName = */ "pkg-name",
        /*interfaceId = */ java.util.Optional.empty(),
        /*contractId = */ contractId,
        /*choice = */ "Noop",
        /*choiceArgument = */ com.daml.ledger.javaapi.data.Unit.getInstance(),
        /*actingParties = */ java.util.Collections.emptyList(),
        /*consuming = */ false,
        /*lastDescendantNodeId = */ lastDescendantNodeId,
        /*exerciseResult = */ com.daml.ledger.javaapi.data.Unit.getInstance(),
        /*implementedInterfaces = */ java.util.Collections.emptyList(),
        /*acsDelta = */ false,
      )

    // Wrap a list of javaApi.Events into a Transaction + TreeUpdateWithMigrationId,
    // honouring the javaApi.Transaction constructor's requirement that events be
    // sorted by nodeId.
    def wrapTx(
        events: Seq[javaApi.Event],
        externalTransactionHash: ByteString = ByteString.EMPTY,
        recordTime: Instant = Instant.parse("2025-01-01T00:00:00.000000Z"),
    ): TreeUpdateWithMigrationId = {
      val sortedEvents = events.sortBy(_.getNodeId.intValue())
      val tx = new javaApi.Transaction(
        /*updateId = */ "update-id",
        /*commandId = */ "",
        /*workflowId = */ "",
        /*effectiveAt = */ recordTime,
        /*events = */ sortedEvents.asJava,
        /*offset = */ 42L,
        /*synchronizerId = */ dummyDomain.toProtoPrimitive,
        /*traceContext = */ com.daml.ledger.api.v2.TraceContextOuterClass.TraceContext.getDefaultInstance,
        /*recordTime = */ recordTime,
        /*externalTransactionHash = */ externalTransactionHash,
        /*trafficCost = */ 0L,
      )
      TreeUpdateWithMigrationId(
        update = UpdateHistoryResponse(
          update = TransactionTreeUpdate(tx),
          synchronizerId = dummyDomain,
        ),
        migrationId = 1L,
      )
    }

    def normalise(update: TreeUpdateWithMigrationId): javaApi.Transaction =
      ScanHttpEncodings
        .makeConsistentAcrossSvs(update, ExternalHashInclusionPolicy.AlwaysInclude, None)
        .update
        .update
        .asInstanceOf[TransactionTreeUpdate]
        .tree

    "renumber a deeply nested tree in in-order traversal order" in {
      // Tree (original node ids chosen so in-order traversal renumbers them):
      //
      //   root (10) ─────────────────────────────────────
      //     ├── childA (20) ──────────────────────────
      //     │     ├── grandA1 (30) [create]
      //     │     └── grandA2 (40) [create]
      //     ├── childB (50) ──────────────────────────
      //     │     └── grandB1 (60) [create]
      //     └── childC (70) [create]
      //
      // In-order traversal visits:
      //   root, childA, grandA1, grandA2, childB, grandB1, childC
      // so remapped node ids are: 0,1,2,3,4,5,6.

      val root = mkExercisedEvent(nodeId = 10, lastDescendantNodeId = 70, contractId = "cid:root")
      val childA =
        mkExercisedEvent(nodeId = 20, lastDescendantNodeId = 40, contractId = "cid:childA")
      val grandA1 = mkCreatedEvent(nodeId = 30, contractId = "cid:grandA1")
      val grandA2 = mkCreatedEvent(nodeId = 40, contractId = "cid:grandA2")
      val childB =
        mkExercisedEvent(nodeId = 50, lastDescendantNodeId = 60, contractId = "cid:childB")
      val grandB1 = mkCreatedEvent(nodeId = 60, contractId = "cid:grandB1")
      val childC = mkCreatedEvent(nodeId = 70, contractId = "cid:childC")

      val update = wrapTx(Seq(root, childA, grandA1, grandA2, childB, grandB1, childC))
      val out = normalise(update)

      // New node ids are dense (no gaps) 0..6 in the order described above.
      def byCid(cid: String): javaApi.Event =
        out.getEvents.asScala.collectFirst {
          case c: javaApi.CreatedEvent if c.getContractId == cid => c: javaApi.Event
          case e: javaApi.ExercisedEvent if e.getContractId == cid => e: javaApi.Event
        }.value

      byCid("cid:root").getNodeId shouldBe 0
      byCid("cid:childA").getNodeId shouldBe 1
      byCid("cid:grandA1").getNodeId shouldBe 2
      byCid("cid:grandA2").getNodeId shouldBe 3
      byCid("cid:childB").getNodeId shouldBe 4
      byCid("cid:grandB1").getNodeId shouldBe 5
      byCid("cid:childC").getNodeId shouldBe 6

      // lastDescendantNodeId is remapped to the deepest descendant's new id.
      byCid("cid:root").asInstanceOf[javaApi.ExercisedEvent].getLastDescendantNodeId shouldBe 6
      byCid("cid:childA").asInstanceOf[javaApi.ExercisedEvent].getLastDescendantNodeId shouldBe 3
      byCid("cid:childB").asInstanceOf[javaApi.ExercisedEvent].getLastDescendantNodeId shouldBe 5

      // The tree's structural accessors agree.
      out.getRootNodeIds.asScala.toSeq shouldBe Seq[java.lang.Integer](0)
      val rootEvent = out.getEventsById.get(0).asInstanceOf[javaApi.ExercisedEvent]
      out.getChildNodeIds(rootEvent).asScala.toSeq shouldBe Seq[java.lang.Integer](1, 4, 6)
      val childAEvent = out.getEventsById.get(1).asInstanceOf[javaApi.ExercisedEvent]
      out.getChildNodeIds(childAEvent).asScala.toSeq shouldBe Seq[java.lang.Integer](2, 3)
      val childBEvent = out.getEventsById.get(4).asInstanceOf[javaApi.ExercisedEvent]
      out.getChildNodeIds(childBEvent).asScala.toSeq shouldBe Seq[java.lang.Integer](5)
    }

    "be idempotent and deterministic regardless of input event ordering" in {
      // Same tree as above.
      val root = mkExercisedEvent(10, 70, "cid:root")
      val childA = mkExercisedEvent(20, 40, "cid:childA")
      val grandA1 = mkCreatedEvent(30, "cid:grandA1")
      val grandA2 = mkCreatedEvent(40, "cid:grandA2")
      val childB = mkExercisedEvent(50, 60, "cid:childB")
      val grandB1 = mkCreatedEvent(60, "cid:grandB1")
      val childC = mkCreatedEvent(70, "cid:childC")
      val allEvents = Seq(root, childA, grandA1, grandA2, childB, grandB1, childC)

      val canonical = normalise(wrapTx(allEvents))

      // Re-running must yield the same result.
      normalise(wrapTx(allEvents)) shouldBe canonical

      // Re-materialising the input in a different iteration order (the
      // Transaction ctor sorts internally by nodeId, so downstream behaviour
      // must not depend on input order) still yields the same result.
      val rng = new Random(0xc0ffee)
      val shuffles = (1 to 20).map(_ => rng.shuffle(allEvents))
      forAll(shuffles) { shuffled =>
        normalise(wrapTx(shuffled)) shouldBe canonical
      }
    }

    "handle a deep linear chain without losing the last-descendant relationship" in {
      // A purely linear chain of exercises with a single terminal create:
      //   e(10) → e(20) → e(30) → e(40) → e(50) → create(60)
      val depth = 6
      val events: Seq[javaApi.Event] = (0 until depth).map { i =>
        val nodeId = (i + 1) * 10
        if (i == depth - 1) mkCreatedEvent(nodeId, s"cid:$i")
        else mkExercisedEvent(nodeId, lastDescendantNodeId = depth * 10, s"cid:$i")
      }

      val out = normalise(wrapTx(events))

      // The chain is renumbered to 0..depth-1.
      out.getEvents.asScala.map(_.getNodeId.intValue()).toVector shouldBe (0 until depth).toVector

      // Every exercise's lastDescendantNodeId points at the terminal create.
      val exercises = out.getEvents.asScala.toVector.collect { case e: javaApi.ExercisedEvent =>
        e
      }
      forAll(exercises)(_.getLastDescendantNodeId shouldBe depth - 1)
    }
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
      trafficSummaryO = None,
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

  "encode traffic summary" in {
    val summary = DbScanVerdictStore.TrafficSummaryT(
      totalTrafficCost = 500L,
      envelopeTrafficSummarys = Seq(
        DbScanVerdictStore.EnvelopeT(trafficCost = 300L, viewIds = Seq(0, 2)),
        DbScanVerdictStore.EnvelopeT(trafficCost = 200L, viewIds = Seq(1)),
      ),
      sequencingTime = CantonTimestamp.now(),
    )

    val encoded = ScanHttpEncodings.encodeTrafficSummary(summary)

    encoded.totalTrafficCost shouldBe 500L
    encoded.envelopeTrafficSummaries.size shouldBe 2

    encoded.envelopeTrafficSummaries(0).trafficCost shouldBe 300L
    encoded.envelopeTrafficSummaries(0).viewIds shouldBe Vector(0, 2)

    encoded.envelopeTrafficSummaries(1).trafficCost shouldBe 200L
    encoded.envelopeTrafficSummaries(1).viewIds shouldBe Vector(1)
  }

  "encode traffic summary with empty envelopes" in {
    val summary = DbScanVerdictStore.TrafficSummaryT(
      totalTrafficCost = 0L,
      envelopeTrafficSummarys = Seq.empty,
      sequencingTime = CantonTimestamp.now(),
    )

    val encoded = ScanHttpEncodings.encodeTrafficSummary(summary)

    encoded.totalTrafficCost shouldBe 0L
    encoded.envelopeTrafficSummaries shouldBe empty
  }

  "external transaction hash" should {
    val extTxnHashHexString = "4d68f590e4a298d9617ebe07b98c6ecbe04b7f3d7a5327f0e0ad4719638302b7"
    val externalTxnHash =
      HexString.parseToByteString(extTxnHashHexString).getOrElse(ByteString.EMPTY)

    val recordTime = Instant.parse("2026-06-30T00:00:00Z")

    def mkTree = TreeUpdateWithMigrationId(
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
            recordTime = recordTime,
            createdEventObservers = Seq.empty,
            externalTxnHash = externalTxnHash,
          )
        ),
        synchronizerId = dummyDomain,
      ),
      migrationId = 42L,
    )

    "return the hash when record time is after threshold" in {
      val thresholdDate = Instant.parse("2026-06-29T23:59:59Z")
      inside(
        ScanHttpEncodings.encodeUpdate(
          mkTree,
          DamlValueEncoding.ProtobufJson,
          ScanHttpEncodings.V1,
          hashInclusionPolicy = ExternalHashInclusionPolicy.ApplyThreshold,
          externalTransactionHashThresholdTime = Some(thresholdDate),
        )
      ) { case httpApi.UpdateHistoryItem.members.UpdateHistoryTransaction(value) =>
        value.externalTransactionHash shouldBe Some(OmitNullString(extTxnHashHexString))
      }
    }

    "return the hash when policy is AlwaysInclude irrespective of record time" in {
      val thresholdDate = Instant.parse("2100-06-30T00:00:01Z")
      inside(
        ScanHttpEncodings.encodeUpdate(
          mkTree,
          DamlValueEncoding.ProtobufJson,
          ScanHttpEncodings.V1,
          hashInclusionPolicy = ExternalHashInclusionPolicy.AlwaysInclude,
          externalTransactionHashThresholdTime = Some(thresholdDate),
        )
      ) { case httpApi.UpdateHistoryItem.members.UpdateHistoryTransaction(value) =>
        value.externalTransactionHash shouldBe Some(OmitNullString(extTxnHashHexString))
      }
    }

    "not return the hash when record time is before threshold" in {
      val thresholdDate = Instant.parse("2026-06-30T00:00:01Z")
      inside(
        ScanHttpEncodings.encodeUpdate(
          mkTree,
          DamlValueEncoding.ProtobufJson,
          ScanHttpEncodings.V1,
          externalTransactionHashThresholdTime = Some(thresholdDate),
        )
      ) { case httpApi.UpdateHistoryItem.members.UpdateHistoryTransaction(value) =>
        value.externalTransactionHash shouldBe None
      }
    }

    "not return the hash when threshold date is not provided" in {
      inside(
        ScanHttpEncodings.encodeUpdate(
          mkTree,
          DamlValueEncoding.ProtobufJson,
          ScanHttpEncodings.V1,
        )
      ) { case httpApi.UpdateHistoryItem.members.UpdateHistoryTransaction(value) =>
        value.externalTransactionHash shouldBe None
      }
    }
  }
}
