package com.daml.network.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{
  ContractMetadata,
  CreatedEvent,
  ExercisedEvent,
  Identifier,
  LedgerOffset,
  TransactionTree,
  TreeEvent,
  Value as damlValue,
  Unit as damlUnit,
}
import com.google.protobuf
import com.daml.network.codegen.java.cc.{api as apiCodegen, coin as coinCodegen}
import com.daml.network.environment.ledger.api.{
  ActiveContract,
  IncompleteTransferEvent,
  TransactionTreeUpdate,
  Transfer,
  TransferEvent,
  TransferUpdate,
}
import com.daml.network.util.{Contract, Trees}
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.wordspec.AsyncWordSpec
import com.daml.lf.data.Numeric

import java.time.Instant
import scala.concurrent.Future
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

abstract class StoreTest extends AsyncWordSpec with BaseTest {

  protected def mkPartyId(name: String) = PartyId.tryFromProtoPrimitive(name + "::dummy")

  protected val svcParty: PartyId = mkPartyId("svc")

  protected def userParty(i: Int) = mkPartyId(s"user-$i")

  protected def providerParty(i: Int) = mkPartyId(s"provider-$i")

  /** @param n must 0-9
    * @param suffix must be a hex string
    */
  protected def validContractId(n: Int, suffix: String = "00"): String = "00" + s"0$n" * 31 + suffix

  protected def appRewardCoupon(
      round: Int,
      provider: PartyId,
      featured: Boolean = false,
      amount: Numeric.Numeric = numeric(1.0),
  ): Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon] =
    Contract(
      identifier = coinCodegen.AppRewardCoupon.TEMPLATE_ID,
      contractId = new coinCodegen.AppRewardCoupon.ContractId(s"de#$round"),
      payload = new coinCodegen.AppRewardCoupon(
        svcParty.toProtoPrimitive,
        provider.toProtoPrimitive,
        featured,
        amount,
        new apiCodegen.v1.round.Round(round),
      ),
      metadata = ContractMetadata.Empty(),
      createArgumentsBlob = protobuf.Any.getDefaultInstance,
    )

  protected def numeric(value: BigDecimal, scale: Int = 10) = {
    Numeric.assertFromBigDecimal(Numeric.Scale.assertFromInt(scale), value)
  }

  protected def validatorRewardCoupon(
      round: Int,
      user: PartyId,
      amount: Numeric.Numeric = numeric(1.0),
  ): Contract[
    coinCodegen.ValidatorRewardCoupon.ContractId,
    coinCodegen.ValidatorRewardCoupon,
  ] =
    Contract(
      identifier = coinCodegen.ValidatorRewardCoupon.TEMPLATE_ID,
      contractId = new coinCodegen.ValidatorRewardCoupon.ContractId(s"der#$round"),
      payload = new coinCodegen.ValidatorRewardCoupon(
        svcParty.toProtoPrimitive,
        user.toProtoPrimitive,
        amount,
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

  protected def toActiveContract[TCid <: ContractId[T], T](
      domain: DomainId,
      contract: Contract[TCid, T],
      counter: Long,
  ): ActiveContract =
    ActiveContract(domain, toCreatedEvent(contract), counter)

  protected def exercisedEvent[TCid <: ContractId[T], T](
      contractId: String,
      templateId: Identifier,
      interfaceId: Option[Identifier],
      choice: String,
      consuming: Boolean,
      argument: damlValue,
      result: damlValue,
  ): ExercisedEvent = {
    new ExercisedEvent(
      eventId = "dummyEventId",
      contractId = contractId,
      templateId = templateId,
      interfaceId = interfaceId.toJava,
      witnessParties = Seq.empty.asJava,
      consuming = consuming,
      choice = choice,
      choiceArgument = argument,
      exerciseResult = result,
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

  protected def withChildren(exercised: ExercisedEvent, childEventIds: Seq[String]) =
    new ExercisedEvent(
      eventId = exercised.getEventId,
      contractId = exercised.getContractId,
      templateId = exercised.getTemplateId,
      interfaceId = exercised.getInterfaceId,
      witnessParties = exercised.getWitnessParties,
      consuming = exercised.isConsuming,
      choice = exercised.getChoice,
      choiceArgument = exercised.getChoiceArgument,
      exerciseResult = exercised.getExerciseResult,
      actingParties = exercised.getActingParties,
      childEventIds = childEventIds.asJava,
    )

  protected val dummyDomain = DomainId.tryFromString("dummy::domain")

  protected val dummy2Domain = DomainId.tryFromString("dummy2::domain")

  protected val defaultEffectiveAt: Instant = CantonTimestamp.Epoch.toInstant

  protected def toIncompleteTransferOut[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      transferOutId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): IncompleteTransferEvent.Out = IncompleteTransferEvent.Out(
    toTransferOutEvent(
      contract.contractId,
      transferOutId,
      source,
      target,
      counter,
    ),
    toCreatedEvent(contract),
  )

  protected def toIncompleteTransferIn[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      transferOutId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): IncompleteTransferEvent.In = IncompleteTransferEvent.In(
    toTransferInEvent(
      contract,
      transferOutId,
      source,
      target,
      counter,
    )
  )

  protected def toTransferOutEvent(
      contractId: ContractId[_],
      transferOutId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): TransferEvent.Out =
    TransferEvent.Out(
      transferOutId = transferOutId,
      submitter = userParty(1),
      contractId = contractId,
      source = source,
      target = target,
      counter = counter,
    )

  protected def toTransferInEvent[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      transferOutId: String,
      source: DomainId,
      target: DomainId,
      counter: Long,
  ): TransferEvent.In = TransferEvent.In(
    transferOutId = transferOutId,
    submitter = userParty(1),
    source = source,
    target = target,
    createdEvent = toCreatedEvent(contract),
    counter = counter,
  )

  protected def mkValidatorRewardCoupon(i: Int) = validatorRewardCoupon(i, userParty(i))

  private var offsetCounter = 0

  private def nextOffset: String = {
    val offset = "%08d".format(offsetCounter)
    offsetCounter += 1
    offset
  }

  protected def mkCreateTx[TCid <: ContractId[T], T](
      offset: String,
      createRequests: Seq[Contract[TCid, T]],
      effectiveAt: Instant,
  ) = mkTx(offset, createRequests.map[TreeEvent](toCreatedEvent), effectiveAt)

  protected def acs[TCid <: ContractId[T], T](
      acs: Seq[(Contract[TCid, T], DomainId, Long)] = Seq.empty,
      incompleteOut: Seq[(Contract[TCid, T], DomainId, DomainId, String, Long)] = Seq.empty,
      incompleteIn: Seq[(Contract[TCid, T], DomainId, DomainId, String, Long)] = Seq.empty,
      acsOffset: String = nextOffset,
  )(implicit store: MultiDomainAcsStore): Future[Unit] = for {
    _ <- store.ingestionSink.initialize()
    _ <- store.ingestionSink.ingestAcs(
      acsOffset,
      acs.map { case (contract, domain, counter) =>
        ActiveContract(domain, toCreatedEvent(contract), counter)
      },
      incompleteOut.map { case (c, sourceDomain, targetDomain, tfid, counter) =>
        toIncompleteTransferOut(
          c,
          tfid,
          sourceDomain,
          targetDomain,
          counter,
        )
      },
      incompleteIn.map { case (c, sourceDomain, targetDomain, tfid, counter) =>
        toIncompleteTransferIn(
          c,
          tfid,
          sourceDomain,
          targetDomain,
          counter,
        )
      },
    )
  } yield ()

  // Convenient syntax to make the tests easy to read.
  protected implicit class DomainSyntax(private val domain: DomainId) {

    def create[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        offset: String = nextOffset,
        txEffectiveAt: Instant = defaultEffectiveAt,
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = mkCreateTx(
        offset,
        Seq(c),
        txEffectiveAt,
      )

      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(tx),
        )
        .map(_ => tx)
    }

    def createMulti[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        offset: String = nextOffset,
        txEffectiveAt: Instant = defaultEffectiveAt,
    )(implicit stores: Seq[MultiDomainAcsStore]): Future[TransactionTree] = {
      val tx = mkCreateTx(
        offset,
        Seq(c),
        txEffectiveAt,
      )
      val txUpdate = TransactionTreeUpdate(tx)
      // Note: runs the futures sequentially in order to get deterministic tests
      stores
        .foldLeft(Future.unit) { (acc, store) =>
          for {
            _ <- acc
            _ <- store.ingestionSink.ingestUpdate(domain, txUpdate)
          } yield ()
        }
        .map(_ => tx)
    }

    def archive[TCid <: ContractId[T], T](
        c: Contract[TCid, T],
        txEffectiveAt: Instant = defaultEffectiveAt,
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = mkTx(nextOffset, Seq(toArchivedEvent(c)), txEffectiveAt)
      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(
            tx
          ),
        )
        .map(_ => tx)
    }

    def ingest(
        makeTx: String => TransactionTree
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = makeTx(nextOffset)
      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(
            tx
          ),
        )
        .map(_ => tx)
    }

    def transferOut[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], DomainId),
        transferId: String,
        counter: Long,
    )(implicit store: MultiDomainAcsStore): Future[Transfer[TransferEvent.Out]] = {
      val transfer = mkTransfer(
        nextOffset,
        toTransferOutEvent(
          contractAndDomain._1.contractId,
          transferId,
          domain,
          contractAndDomain._2,
          counter,
        ),
      )

      store.ingestionSink
        .ingestUpdate(
          domain,
          TransferUpdate(transfer),
        )
        .map(_ => transfer)
    }

    def transferIn[TCid <: ContractId[T], T](
        contractAndDomain: (Contract[TCid, T], DomainId),
        transferId: String,
        counter: Long,
    )(implicit store: MultiDomainAcsStore): Future[Transfer[TransferEvent.In]] = {
      val transfer = mkTransfer(
        nextOffset,
        toTransferInEvent(
          contractAndDomain._1,
          transferId,
          contractAndDomain._2,
          domain,
          counter,
        ),
      )

      store.ingestionSink
        .ingestUpdate(
          domain,
          TransferUpdate(transfer),
        )
        .map(_ => transfer)
    }

    def exercise[TCid <: ContractId[T], T](
        contract: Contract[TCid, T],
        interfaceId: Option[Identifier],
        choiceName: String,
        choiceArgument: damlValue,
        exerciseResult: damlValue,
        offset: String = nextOffset,
        txEffectiveAt: Instant = defaultEffectiveAt,
    )(implicit store: MultiDomainAcsStore): Future[TransactionTree] = {
      val tx = mkTx(
        offset,
        Seq(mkExercise(contract, interfaceId, choiceName, choiceArgument, exerciseResult)),
        txEffectiveAt,
      )
      store.ingestionSink
        .ingestUpdate(
          domain,
          TransactionTreeUpdate(tx),
        )
        .map(_ => tx)
    }
  }

  private def nextTransactionId(): String = java.util.UUID.randomUUID().toString.replace("-", "")

  protected def mkTx(
      offset: String,
      events: Seq[TreeEvent],
      effectiveAt: Instant = defaultEffectiveAt,
  ): TransactionTree = {
    val transactionId = nextTransactionId()
    val eventsWithId = events.zipWithIndex.map { case (e, i) =>
      withEventId(e, s"$transactionId:$i")
    }
    val eventsById = eventsWithId.map(e => e.getEventId -> e).toMap
    val rootEventIds = eventsWithId.map(_.getEventId)
    new TransactionTree(
      transactionId = transactionId,
      commandId = "",
      workflowId = "",
      effectiveAt = effectiveAt,
      offset = offset,
      eventsById = eventsById.asJava,
      rootEventIds = rootEventIds.asJava,
    )
  }

  protected def mkExerciseTx(
      offset: String,
      root: ExercisedEvent,
      children: Seq[TreeEvent],
      effectiveAt: Instant = defaultEffectiveAt,
  ): TransactionTree = {
    val transactionId = nextTransactionId()
    val childrenWithId = children.zipWithIndex.map { case (e, i) =>
      withEventId(e, s"$transactionId:${i + 1}")
    }
    val rootWithId =
      withEventId(withChildren(root, childrenWithId.map(_.getEventId)), s"$transactionId:0")
    val eventsById = (rootWithId +: childrenWithId).map(e => e.getEventId -> e).toMap
    val rootEventIds = Seq(rootWithId.getEventId)
    new TransactionTree(
      transactionId = transactionId,
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

  protected def mkExercise[TCid <: ContractId[T], T](
      contract: Contract[TCid, T],
      interfaceId: Option[Identifier],
      choiceName: String,
      choiceArgument: damlValue,
      exerciseResult: damlValue,
  ): TreeEvent =
    new ExercisedEvent(
      eventId = "dummyEventId",
      contractId = contract.contractId.contractId,
      templateId = contract.identifier,
      interfaceId = interfaceId.toJava,
      witnessParties = Seq.empty.asJava,
      consuming = false,
      choice = choiceName,
      choiceArgument = choiceArgument,
      exerciseResult = exerciseResult,
      actingParties = Seq.empty.asJava,
      childEventIds = Seq.empty.asJava,
    )
}

object StoreTest {
  case class TestTxLogIndexRecord(
      optOffset: Option[String],
      eventId: String,
  ) extends TxLogStore.IndexRecord

  case class TestTxLogEntry(
      indexRecord: TestTxLogIndexRecord,
      payload: String,
  ) extends TxLogStore.Entry[TestTxLogIndexRecord]

  object TestTxLogStoreParser extends TxLogStore.Parser[TestTxLogIndexRecord, TestTxLogEntry] {
    def parseAcs(
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteTransferEvent.Out],
        incompleteIn: Seq[IncompleteTransferEvent.In],
    )(implicit
        tc: TraceContext
    ): Seq[(DomainId, TestTxLogEntry)] = Seq.empty

    override def tryParse(tx: TransactionTree)(implicit tc: TraceContext): Seq[TestTxLogEntry] = {
      Trees.foldTree(tx, Seq.empty[TestTxLogEntry])(
        onCreate = (res, event, _) => {
          res :+
            TestTxLogEntry(
              indexRecord =
                TestTxLogIndexRecord(optOffset = Some(tx.getOffset), eventId = event.getEventId),
              payload = event.getEventId,
            )
        },
        onExercise = (res, _, _) => res,
      )
    }

    override def error(offset: String, eventId: String): Option[TestTxLogEntry] = None
  }
}
