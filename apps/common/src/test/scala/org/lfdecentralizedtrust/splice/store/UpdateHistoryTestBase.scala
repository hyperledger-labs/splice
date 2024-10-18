package org.lfdecentralizedtrust.splice.store

import com.daml.ledger.api.v2.TraceContextOuterClass
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, TransactionTree, TreeEvent}
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient.GetTreeUpdatesResponse
import org.lfdecentralizedtrust.splice.environment.ledger.api.ReassignmentEvent.{Assign, Unassign}
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  LedgerClient,
  Reassignment,
  ReassignmentEvent,
  ReassignmentUpdate,
  TransactionTreeUpdate,
}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.store.db.{AcsJdbcTypes, AcsTables, SplicePostgresTest}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.topology.*
import com.digitalasset.canton.{HasActorSystem, HasExecutionContext}
import com.google.protobuf.ByteString
import org.scalatest.Assertion

import scala.concurrent.Future
import scala.jdk.CollectionConverters.*

abstract class UpdateHistoryTestBase
    extends StoreTest
    with HasExecutionContext
    with StoreErrors
    with HasActorSystem
    with SplicePostgresTest
    with AcsJdbcTypes
    with AcsTables {

  import UpdateHistoryTestBase.*

  protected def create(
      domain: DomainId,
      contractId: String,
      offset: Long,
      party: PartyId,
      store: UpdateHistory,
      txEffectiveAt: CantonTimestamp,
  ) = {
    DomainSyntax(domain).create(
      c = appRewardCoupon(
        round = 0,
        provider = party,
        contractId = contractId,
      ),
      offset = offset,
      txEffectiveAt = txEffectiveAt.toInstant,
      createdEventSignatories = Seq(party),
      recordTime = txEffectiveAt.toInstant,
    )(
      store
    )
  }

  protected def assign(
      domainTo: DomainId,
      domainFrom: DomainId,
      contractId: String,
      offset: Long,
      party: PartyId,
      counter: Long,
      reassignmentId: String,
      store: UpdateHistory,
      txEffectiveAt: CantonTimestamp,
  ) = {
    DomainSyntax(domainTo).assign(
      contractAndDomain = (
        appRewardCoupon(
          round = 0,
          provider = party,
          contractId = contractId,
        ),
        domainFrom,
      ),
      offset = offset,
      counter = counter,
      reassignmentId = reassignmentId,
      recordTime = txEffectiveAt,
    )(
      store
    )
  }

  protected def unassign(
      domainFrom: DomainId,
      domainTo: DomainId,
      contractId: String,
      offset: Long,
      party: PartyId,
      counter: Long,
      reassignmentId: String,
      store: UpdateHistory,
      txEffectiveAt: CantonTimestamp,
  ) = {
    DomainSyntax(domainFrom).unassign(
      contractAndDomain = (
        appRewardCoupon(
          round = 0,
          provider = party,
          contractId = contractId,
        ),
        domainTo,
      ),
      offset = offset,
      counter = counter,
      reassignmentId = reassignmentId,
      recordTime = txEffectiveAt,
    )(
      store
    )
  }

  protected def createMulti(
      domain: DomainId,
      contractId: String,
      offset: Long,
      party: PartyId,
      stores: Seq[UpdateHistory],
      txEffectiveAt: CantonTimestamp,
  ) = {
    DomainSyntax(domain).createMulti(
      c = appRewardCoupon(
        round = 0,
        provider = party,
        contractId = contractId,
      ),
      offset = offset,
      txEffectiveAt = txEffectiveAt.toInstant,
      createdEventSignatories = Seq(party),
      recordTime = txEffectiveAt.toInstant,
    )(
      stores
    )
  }

  protected def validOffset(i: Int) = {
    assert(i > 0)
    i.toLong
  }

  protected def time(i: Int): CantonTimestamp =
    CantonTimestamp.assertFromInstant(defaultEffectiveAt.plusMillis(i.toLong))

  // Universal begin offset (strictly smaller than any offset used in this suite)
  protected val beginOffset = "0".repeat(16)
  // Universal end offset (strictly larger than any offset used in this suite)
  protected val endOffset = "9".repeat(16)

  protected def singleRootEvent(tree: TransactionTree): TreeEvent = {
    val rootEventIds = tree.getRootEventIds.asScala
    rootEventIds.length should be(1)
    val rootEventId = rootEventIds.headOption.value
    tree.getEventsById.get(rootEventId)
  }
  protected def checkUpdates(
      actual: Seq[LedgerClient.GetTreeUpdatesResponse],
      expected: Seq[ExpectedUpdate],
  ): Assertion = {
    actual.length should be(expected.length)
    actual.zip(expected).foreach {
      case (GetTreeUpdatesResponse(TransactionTreeUpdate(tree), domain), expected) =>
        val rootEvent = singleRootEvent(tree)
        (rootEvent, expected) match {
          case (rootEvent: CreatedEvent, ExpectedCreate(cid, domainId)) =>
            rootEvent.getContractId should be(cid)
            domain should be(domainId)
          case (rootEvent: ExercisedEvent, ExpectedExercise(cid, domainId, choice)) =>
            rootEvent.getContractId should be(cid)
            rootEvent.getChoice should be(choice)
            domain should be(domainId)
          case (event, expected) =>
            throw new RuntimeException(s"Unexpected event type. event: $event, expected: $expected")
        }
      case (GetTreeUpdatesResponse(ReassignmentUpdate(update), domain), expected) =>
        (update.event, expected) match {
          case (unassign: ReassignmentEvent.Unassign, expected: ExpectedUnassign) =>
            unassign.contractId.contractId should be(expected.cid)
            domain should be(expected.domainId)
            unassign.source should be(expected.domainId)
            unassign.target should be(expected.targetDomain)
          case (assign: ReassignmentEvent.Assign, expected: ExpectedAssign) =>
            assign.createdEvent.getContractId should be(expected.cid)
            assign.source should be(expected.sourceDomain)
            assign.target should be(expected.domainId)
          case (event, expected) =>
            throw new RuntimeException(
              s"Unexpected reassignment type. event: $event, expected: $expected"
            )
        }
      case _ => throw new RuntimeException("Unexpected update type")
    }
    succeed
  }

  protected def initStore(implicit store: UpdateHistory): Future[Unit] = {
    store.testIngestionSink.initialize().map(_ => ())
  }

  protected def mkStore(
      updateStreamParty: PartyId = party1,
      domainMigrationId: Long = migration1,
      participantId: ParticipantId = participant1,
      storeName: String = storeName1,
  ): UpdateHistory = {
    new UpdateHistory(
      storage,
      DomainMigrationInfo(
        domainMigrationId,
        None,
      ),
      storeName,
      participantId,
      updateStreamParty,
      loggerFactory,
      enableissue12777Workaround = true,
    )
  }

  override def cleanDb(storage: DbStorage): Future[?] =
    for {
      _ <- resetAllAppTables(storage)
    } yield ()

  protected val storeName1 = "UpdateHistoryTestStore1"
  protected val party1 = userParty(1)
  protected val party2 = userParty(2)

  protected val migration1 = 1L
  protected val migration2 = 2L

  protected val domain1: DomainId = DomainId.tryFromString("domain1::domain")
  protected val domain2: DomainId = DomainId.tryFromString("domain2::domain")

  protected val cid1 = validContractId(1)
  protected val cid2 = validContractId(2)

  protected val offset1 = validOffset(1)
  protected val offset2 = validOffset(2)

  protected val participant1 = ParticipantId("participant1")
  protected val participant2 = ParticipantId("participant2")

  protected val reassignmentId1 = "%08d".format(1)
}

object UpdateHistoryTestBase {
  sealed trait ExpectedUpdate extends Product with Serializable
  final case class ExpectedCreate(cid: String, domainId: DomainId) extends ExpectedUpdate
  final case class ExpectedExercise(cid: String, domainId: DomainId, choice: String)
      extends ExpectedUpdate

  final case class ExpectedAssign(cid: String, sourceDomain: DomainId, domainId: DomainId)
      extends ExpectedUpdate

  final case class ExpectedUnassign(cid: String, domainId: DomainId, targetDomain: DomainId)
      extends ExpectedUpdate

  sealed trait LostDataMode

  /** Data lost during ingestion into the UpdateHistory database,
    * because the database schema does not store all fields.
    */
  final case object LostInStoreIngestion extends LostDataMode

  /** Data lost during encoding of data read from the database into HTTP scan API responses.
    *
    * Currently, this affects the `commandId` field in the `TransactionTree` object.
    * For debug purposes, it is useful to have this field in the DB, but
    * since it's participant-local, it does not make sense to expose it in scan -
    * otherwise different scan instances would return different data.
    */
  final case object LostInScanApi extends LostDataMode

  def withoutLostData(
      update: TreeUpdateWithMigrationId,
      mode: LostDataMode,
  ): TreeUpdateWithMigrationId = {
    TreeUpdateWithMigrationId(
      UpdateHistoryTestBase.withoutLostData(update.update, mode),
      update.migrationId,
    )
  }

  def withoutLostData(
      response: GetTreeUpdatesResponse,
      mode: LostDataMode,
  ): GetTreeUpdatesResponse = {
    response match {
      case GetTreeUpdatesResponse(TransactionTreeUpdate(tree), domain) =>
        GetTreeUpdatesResponse(TransactionTreeUpdate(withoutLostData(tree, mode)), domain)
      case GetTreeUpdatesResponse(ReassignmentUpdate(transfer), domain) =>
        GetTreeUpdatesResponse(ReassignmentUpdate(withoutLostData(transfer)), domain)
      case _ => throw new RuntimeException("Invalid update type")
    }
  }

  private def withoutLostData(tree: TransactionTree, mode: LostDataMode): TransactionTree = {
    new TransactionTree(
      /*updateId = */ tree.getUpdateId,
      /*commandId = */ if (mode == LostInScanApi) { "" }
      else {
        tree.getCommandId
      }, // Command IDs are participant-local, so not preserved for backfills
      /*workflowId = */ tree.getWorkflowId,
      /*effectiveAt = */ tree.getEffectiveAt,
      /*offset = */ tree.getOffset,
      /*eventsById = */ tree.getEventsById.asScala.view.mapValues(withoutLostData).toMap.asJava,
      /*rootEventIds = */ tree.getRootEventIds,
      /*domainId = */ tree.getDomainId,

      // We don't care about tracing information in the update history.
      /*traceContext = */ TraceContextOuterClass.TraceContext.getDefaultInstance, // Not preserved

      /*recordTime = */ tree.getRecordTime,
    )
  }

  private def withoutLostData(event: TreeEvent): TreeEvent = {
    event match {
      case created: CreatedEvent =>
        withoutLostData(created)
      case exercised: ExercisedEvent =>
        withoutLostData(exercised)
      case _ => throw new RuntimeException("Invalid event type")
    }
  }

  private def withoutLostData(created: CreatedEvent): CreatedEvent = {
    new CreatedEvent(
      // The witnesses returned by the API is the intersection of actual witnesses according
      // to the daml model with the subscribing parties, and we're always subscribing as a single party,
      // so this would always end up being the operator party of our own application which is not very useful.
      /*witnessParties = */ java.util.Collections.emptyList(), // Not preserved

      /*eventId = */ created.getEventId,
      /*templateId = */ created.getTemplateId,
      /*packageName = */ created.getPackageName,
      /*contractId = */ created.getContractId,
      /*arguments = */ created.getArguments,

      // Binary data used for explicit disclosure of active contracts. Not useful for historical data.
      /*createdEventBlob = */ ByteString.EMPTY, // Not preserved

      // None of our daml models use interfaces.
      // Interface views can be computed from the contract payload if you know the daml model.
      // The ledger API only returns interface views that the application has subscribed to, i.e.,
      // our applications will not see interface views of 3rd party daml code.
      /*interfaceViews = */ java.util.Collections.emptyMap(), // Not preserved
      /*failedInterfaceViews = */ java.util.Collections.emptyMap(), // Not preserved

      /*contractKey = */ created.getContractKey,
      /*signatories = */ created.getSignatories,
      /*observers = */ created.getObservers,
      /*createdAt = */ created.getCreatedAt,
    )
  }

  private def withoutLostData(exercised: ExercisedEvent): ExercisedEvent = {
    new ExercisedEvent(
      // The witnesses returned by the API is the intersection of actual witnesses according
      // to the daml model with the subscribing parties, and we're always subscribing as a single party,
      // so this would always end up being the operator party of our own application which is not very useful.
      /*witnessParties = */ java.util.Collections.emptyList(), // Not preserved

      /*eventId = */ exercised.getEventId,
      /*templateId = */ exercised.getTemplateId,
      /*packageName = */ exercised.getPackageName,
      /*interfaceId = */ exercised.getInterfaceId,
      /*contractId = */ exercised.getContractId,
      /*choice = */ exercised.getChoice,
      /*choiceArgument = */ exercised.getChoiceArgument,
      /*actingParties = */ exercised.getActingParties,
      /*consuming = */ exercised.isConsuming,
      /*childEventIds = */ exercised.getChildEventIds,
      /*exerciseResult = */ exercised.getExerciseResult,
    )
  }

  private def withoutLostData(
      transfer: Reassignment[ReassignmentEvent]
  ): Reassignment[ReassignmentEvent] = {
    transfer match {
      case Reassignment(updateId, offset, recordTime, assign: Assign) =>
        Reassignment(
          updateId,
          offset,
          recordTime,
          assign.copy(
            createdEvent = withoutLostData(assign.createdEvent)
          ),
        )
      case Reassignment(updateId, offset, recordTime, unassign: Unassign) =>
        Reassignment(
          updateId,
          offset,
          recordTime,
          unassign,
        )
      case _ => throw new RuntimeException("Invalid transfer type")
    }
  }
}
