package com.daml.network.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{
  ContractMetadata,
  CreatedEvent,
  ExercisedEvent,
  LedgerOffset,
  TransactionTree,
  TreeEvent,
  Unit as damlUnit,
}
import com.google.protobuf
import com.daml.network.codegen.java.cc.{api as apiCodegen, coin as directoryCodegen}
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{Transfer, TransferEvent}
import com.daml.network.util.{Contract, Trees}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.wordspec.AsyncWordSpec

import java.time.Instant
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

abstract class StoreTest extends AsyncWordSpec with BaseTest {

  protected def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")

  protected val svcParty: PartyId = mkPartyId("svc")

  protected def userParty(i: Int) = mkPartyId(s"user-$i")

  protected def providerParty(i: Int) = mkPartyId(s"provider-$i")

  protected def appRewardCoupon(
      round: Int,
      provider: PartyId,
      featured: Boolean = false,
  ): Contract[directoryCodegen.AppRewardCoupon.ContractId, directoryCodegen.AppRewardCoupon] =
    Contract(
      identifier = directoryCodegen.AppRewardCoupon.TEMPLATE_ID,
      contractId = new directoryCodegen.AppRewardCoupon.ContractId(s"de#$round"),
      payload = new directoryCodegen.AppRewardCoupon(
        svcParty.toProtoPrimitive,
        provider.toProtoPrimitive,
        featured,
        BigDecimal(1.0).bigDecimal,
        new apiCodegen.v1.round.Round(round),
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  protected def validatorRewardCoupon(
      round: Int,
      user: PartyId,
  ): Contract[
    directoryCodegen.ValidatorRewardCoupon.ContractId,
    directoryCodegen.ValidatorRewardCoupon,
  ] =
    Contract(
      identifier = directoryCodegen.ValidatorRewardCoupon.TEMPLATE_ID,
      contractId = new directoryCodegen.ValidatorRewardCoupon.ContractId(s"der#$round"),
      payload = new directoryCodegen.ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        user.toProtoPrimitive,
        BigDecimal(1.0).bigDecimal,
        new apiCodegen.v1.round.Round(round),
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  protected def toCreatedEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T]
  ): CreatedEvent = {
    val contractP = contract.toProtoV0
    new CreatedEvent(
      eventId = "dummyEventId",
      contractId = contractP.contractId,
      interfaceViews = Map.empty.asJava,
      failedInterfaceViews = Map.empty.asJava,
      templateId = contract.identifier,
      arguments = contract.payload.toValue,
      createArgumentsBlob = contract.createArgumentsBlob,
      contractMetadata = contract.metadata,
      witnessParties = Seq.empty.asJava,
      signatories = Seq.empty.asJava,
      observers = Seq.empty.asJava,
      agreementText = None.toJava,
      contractKey = None.toJava,
    )
  }

  protected def toArchivedEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T]
  ): ExercisedEvent = {
    new ExercisedEvent(
      eventId = "dummyEventId",
      contractId = contract.contractId.contractId,
      templateId = contract.identifier,
      interfaceId = None.toJava,
      witnessParties = Seq.empty.asJava,
      consuming = true,
      choice = "DummyChoiceName",
      choiceArgument = damlUnit.getInstance(),
      exerciseResult = damlUnit.getInstance(),
      actingParties = Seq.empty.asJava,
      childEventIds = Seq.empty.asJava,
    )
  }

  protected def withEventId(
      event: TreeEvent,
      eventId: String,
  ): TreeEvent = event match {
    case created: CreatedEvent =>
      new CreatedEvent(
        eventId = eventId,
        contractId = created.getContractId,
        interfaceViews = created.getInterfaceViews,
        failedInterfaceViews = created.getFailedInterfaceViews,
        templateId = created.getTemplateId,
        arguments = created.getArguments,
        createArgumentsBlob = created.getCreateArgumentsBlob,
        contractMetadata = created.getContractMetadata,
        witnessParties = created.getWitnessParties,
        signatories = created.getSignatories,
        observers = created.getObservers,
        agreementText = created.getAgreementText,
        contractKey = created.getContractKey,
      )
    case exercised: ExercisedEvent =>
      new ExercisedEvent(
        eventId = eventId,
        contractId = exercised.getContractId,
        templateId = exercised.getTemplateId,
        interfaceId = exercised.getInterfaceId,
        witnessParties = exercised.getWitnessParties,
        consuming = exercised.isConsuming,
        choice = exercised.getChoice,
        choiceArgument = exercised.getChoiceArgument,
        exerciseResult = exercised.getExerciseResult,
        actingParties = exercised.getActingParties,
        childEventIds = exercised.getChildEventIds,
      )
    case _ => sys.error("Catch-all required because of no exhaustiveness checks with Java")
  }

  protected val dummyDomain = DomainId.tryFromString("dummy::domain")

  protected val dummy2Domain = DomainId.tryFromString("dummy2::domain")

  protected val effectiveAt: Instant = CantonTimestamp.Epoch.toInstant

  protected def toTransferOutEvent(
      contractId: ContractId[_],
      transferOutId: String,
      source: DomainId = dummyDomain,
      target: DomainId = dummyDomain,
  ): TransferEvent.Out =
    TransferEvent.Out(
      transferOutId = transferOutId,
      submitter = userParty(1),
      contractId = contractId,
      source = source,
      target = target,
    )

  protected def toTransferInEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      transferOutId: String,
      source: DomainId = dummyDomain,
      target: DomainId = dummyDomain,
  ): TransferEvent.In = TransferEvent.In(
    transferOutId = transferOutId,
    submitter = userParty(1),
    source = source,
    target = target,
    createdEvent = toCreatedEvent(contract),
  )

  protected def mkValidatorRewardCoupon(i: Int) = validatorRewardCoupon(i, userParty(i))

  protected def mkTx(offset: String, events: Seq[TreeEvent]): TransactionTree = {
    val eventsWithId = events.zipWithIndex.map { case (e, i) => withEventId(e, s"$offset:$i") }
    val eventsById = eventsWithId.map(e => e.getEventId -> e).toMap
    val rootEventIds = eventsWithId.map(_.getEventId)
    new TransactionTree(
      transactionId = "",
      commandId = "",
      workflowId = "",
      effectiveAt = effectiveAt,
      offset = offset,
      eventsById = eventsById.asJava,
      rootEventIds = rootEventIds.asJava,
    )
  }

  protected def mkTransfer[T <: TransferEvent](offset: String, event: T): Transfer[T] =
    Transfer(
      updateId = "",
      offset = new LedgerOffset.Absolute(offset),
      event = event,
    )

  case class TestTxLogIndexRecord(
      offset: String,
      eventId: String,
  ) extends TxLogStore.IndexRecord

  case class TestTxLogEntry(
      indexRecord: TestTxLogIndexRecord,
      payload: String,
  ) extends TxLogStore.Entry[TestTxLogIndexRecord]

  object TestTxLogStoreParser extends TxLogStore.Parser[TestTxLogIndexRecord, TestTxLogEntry] {
    override def parse(tx: TransactionTree)(implicit tc: TraceContext): Seq[TestTxLogEntry] = {
      Trees.foldTree(tx, Seq.empty[TestTxLogEntry])(
        onCreate = (res, event, _) => {
          res :+
            TestTxLogEntry(
              indexRecord = TestTxLogIndexRecord(offset = tx.getOffset, eventId = event.getEventId),
              payload = event.getEventId,
            )
        },
        onExercise = (res, _, _) => res,
      )
    }
  }
}
