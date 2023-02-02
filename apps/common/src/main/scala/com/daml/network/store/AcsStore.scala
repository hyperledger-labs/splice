package com.daml.network.store

import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse
import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.Contract
import com.daml.ledger.javaapi.data.codegen.{
  ContractCompanion,
  ContractId,
  DamlRecord,
  InterfaceCompanion,
  Contract as CodegenContract,
}
import com.daml.ledger.javaapi.data.{
  CreatedEvent,
  Filter,
  FiltersByParty,
  Identifier,
  InclusiveFilter,
  Template,
  TransactionFilter,
  TransactionTree,
}
import com.daml.network.util.JavaContract
import com.daml.network.util.PrettyInstances.PrettyContractId
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.annotation.nowarn
import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

/** A store for querying active contract sets.
  *
  * Only events contained in the store's [[com.daml.network.store.AcsStore.ContractFilter]] are ingested,
  * which serves to pre-filter contracts and thereby simplifies queries against the store.
  *
  * All functions that return a [[com.daml.network.store.AcsStore.QueryResult]] are logically
  * computed against a snapshot of the ledger's ACS. They also return the offset of that snapshot,
  * which can be used as a deduplication offset in command deduplication. We add a `withOffset`
  * suffix to methods returning a [[com.daml.network.store.AcsStore.QueryResult]] to distinguish
  * them from the method that only returns the value.
  *
  * We recommend to only add both the value-only and the `withOffset` methods on-demand, as you write your client code.
  */
trait AcsStore extends AutoCloseable {
  import AcsStore.*

  /** Lookup a contract by id. */
  def lookupContractById[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T]): Future[Option[JavaContract[TCid, T]]]

  /** Lookup a contract's interface view by id. */
  def lookupContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id): Future[Option[JavaContract[Id, View]]]

  /** Get a contract by id.
    *
    * Throws [[Status.NOT_FOUND]] if no such contract exists.
    */
  def getContractById[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(id: ContractId[T])(implicit ec: ExecutionContext): Future[JavaContract[TCid, T]] =
    lookupContractById(templateCompanion)(id).map(result =>
      result.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(
            PrettyContractId(templateCompanion.TEMPLATE_ID, id).toString
          )
          .asRuntimeException
      )
    )

  /** Get a contract's interface view by id.
    *
    * Throws [[Status.NOT_FOUND]] if no such contract view exists.
    */
  def getContractById[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(id: Id)(implicit ec: ExecutionContext): Future[JavaContract[Id, View]] =
    lookupContractById(interfaceCompanion)(id).map(result =>
      result.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(
            PrettyContractId(interfaceCompanion.TEMPLATE_ID, id).toString
          )
          .asRuntimeException
      )
    )

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContractWithOffset[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(
      p: JavaContract[TCid, T] => Boolean = (_: JavaContract[TCid, T]) => true
  ): Future[QueryResult[Option[JavaContract[TCid, T]]]]

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContract[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(
      p: JavaContract[TCid, T] => Boolean = (_: JavaContract[TCid, T]) => true
  )(implicit ec: ExecutionContext): Future[Option[JavaContract[TCid, T]]] =
    findContractWithOffset(templateCompanion)(p).map(_.value)

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContractWithOffset[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(p: JavaContract[Id, View] => Boolean): Future[QueryResult[Option[JavaContract[Id, View]]]]

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContract[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(p: JavaContract[Id, View] => Boolean)(implicit
      ec: ExecutionContext
  ): Future[Option[JavaContract[Id, View]]] =
    findContractWithOffset(interfaceCompanion)(p).map(_.value)

  /** List all active contracts of the given template.
    *
    * Beware that for the in-memory implementation, this method iterates through all the created events in the store.
    * TODO(M3-83): add indexes for ^.
    */
  // TODO(M3-83): add a limit parameter
  def listContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T],
      filter: JavaContract[TCid, T] => Boolean = (_: JavaContract[TCid, T]) => true,
      limit: Option[Long] = None,
  ): Future[Seq[JavaContract[TCid, T]]]

  /** List all active contracts of the given interface. */
  def listContractsI[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View],
      filter: JavaContract[Id, View] => Boolean,
      limit: Option[Long] = None,
  ): Future[Seq[JavaContract[Id, View]]]

  /** A stream of contracts of the given template.
    *
    * The stream starts with all contracts from an ACS snapshot and then emits contracts created in transactions
    * with the special rule that contracts whose archival is already known are not emitted.
    *
    * '''emits''' whenever the store knows or learns about a create event of a contract matching the template that is
    * (a) more recent than the previously emitted one (or it is the oldest one) and
    * (b) whose archive event is not known to the store
    *
    * '''completes''' never, as it tails newly ingested transactions
    */
  def streamContracts[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Source[JavaContract[TCid, T], NotUsed]

  /** A stream of contracts of the given interface.
    *
    * The stream starts with all contracts from an ACS snapshot and then emits contracts created in transactions
    * with the special rule that contracts whose archival is already known are not emitted.
    *
    * '''emits''' whenever the store knows or learns about a create event of a contract matching the interface that is
    * (a) more recent than the previously emitted one (or it is the oldest one) and
    * (b) whose archive event is not known to the store
    *
    * '''completes''' never, as it tails newly ingested transactions
    */
  def streamContracts[I, Id <: ContractId[I], View <: DamlRecord[View]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  ): Source[JavaContract[Id, View], NotUsed]

  /** Signal when the store has ingested at least one contract of the given template. */
  def signalWhenIngested[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  ): Future[Unit]

  /** Signal when the store has finished ingesting ledger data from the given offset or a larger one. */
  def signalWhenIngested(offset: String)(implicit tc: TraceContext): Future[Unit]
}

object AcsStore {

  // TODO (#2619) remove along with FutureAcsStore
  private[network] def futureStore(store: Future[AcsStore])(implicit
      ec: ExecutionContext
  ): AcsStore =
    new FutureAcsStore(store)

  // TODO (M3-18) Remove the hacky interface decoding machinery once we have proper interface support for multi-domain.
  abstract class InterfaceDecoder {
    def fromCreatedEvent[I, Id <: ContractId[I], View <: DamlRecord[_]](
        companion: InterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): Option[JavaContract[Id, View]]
  }

  final case class InterfaceImplementation[I, Id <: ContractId[I], View <: DamlRecord[
    _
  ], TC <: CodegenContract[TCid, Tmpl], TCid <: ContractId[Tmpl], Tmpl <: Template](
      companion: ContractCompanion[TC, TCid, Tmpl],
      view: Tmpl => View,
  ) {
    def toInterfaceContract(
        interfaceCompanion: InterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): JavaContract[Id, View] = {
      val templateContract: JavaContract[TCid, Tmpl] =
        JavaContract.fromCodegenContract[TCid, Tmpl](companion.fromCreatedEvent(ev))
      JavaContract[Id, View](
        interfaceCompanion.TEMPLATE_ID,
        interfaceCompanion.toContractId(new ContractId(templateContract.contractId.contractId)),
        view(templateContract.payload),
      )
    }
  }

  object InterfaceImplementation {
    def apply[I, Id <: ContractId[I], View <: DamlRecord[_], TC <: CodegenContract[
      TCid,
      Tmpl,
    ], TCid <: ContractId[Tmpl], Tmpl <: Template](
        companion: ContractCompanion[TC, TCid, Tmpl]
    ): (Tmpl => View) => InterfaceImplementation[I, Id, View, TC, TCid, Tmpl] =
      view => InterfaceImplementation(companion, view)
  }

  /** A query result computed as-of a specific ledger API offset. */
  case class QueryResult[A](
      offset: String,
      value: A,
  ) {
    def map[B](f: A => B): QueryResult[B] =
      copy(
        value = f(this.value)
      )
  }

  /** A sink for ingesting an initial ACS snapshot and transactions changing it. */
  trait IngestionSink {

    /** The filter required for ingestion. */
    def ingestionFilter: IngestionFilter

    /** Ingest create events that are part of the initial active contract snapshot ingestion */
    def ingestActiveContracts(
        events: Seq[CreatedEvent]
    )(implicit traceContext: TraceContext): Future[Unit]

    /** Signal the end of ingesting the active contract snapshot. */
    def switchToIngestingTransactions(
        acsOffset: String
    )(implicit traceContext: TraceContext): Future[Unit]

    /** The last offset that was ingested by this sink.
      *
      * Expected to be used by ingestion services to determine from where they should continue ingesting.
      */
    def getLastIngestedOffset: Future[Option[String]]

    /** Ingest a transaction served by the transaction stream. */
    def ingestTransaction(tx: TransactionTree)(implicit traceContext: TraceContext): Future[Unit]

    /** Ingest a transfer in/out served by the update stream. */
    def ingestTransfer(
        transfer: GetTreeUpdatesResponse.Transfer[GetTreeUpdatesResponse.TransferEvent]
    )(implicit
        traceContext: TraceContext
    ): Future[Unit]

    def ingestUpdate(transfer: GetTreeUpdatesResponse.TreeUpdate)(implicit
        traceContext: TraceContext
    ): Future[Unit]
  }

  /** Static specification of a set of create events in scope for ingestion into an AcsStore. */
  trait ContractFilter {

    /** The filter required for ingestion into this store. */
    def ingestionFilter: IngestionFilter

    /** Whether the event is in scope. */
    def contains(ev: CreatedEvent): Boolean

    /** Whether the scope might contain an event of the given template. */
    def mightContain[TC, TCid, T](templateCompanion: ContractCompanion[TC, TCid, T]): Boolean

    /** Whether the scope might contain an event of the given interface. */
    def mightContain[I, Id, View](interfaceCompanion: InterfaceCompanion[I, Id, View]): Boolean

    def decodeInterface[I, Id <: ContractId[I], View <: DamlRecord[_]](
        interfaceCompanion: InterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): Option[JavaContract[Id, View]]
  }

  /** A helper to easily construct a [[ContractFilter]] for a single party. */
  case class SimpleContractFilter(
      primaryParty: PartyId,
      templateFilters: immutable.Map[Identifier, CreatedEvent => Boolean],
      interfaceFilters: immutable.Map[Identifier, (CreatedEvent => Boolean, InterfaceDecoder)] =
        Map.empty,
  ) extends ContractFilter {

    override val ingestionFilter =
      IngestionFilter(
        primaryParty,
        templateIds = templateFilters.keySet,
        interfaceIds = interfaceFilters.keySet,
      )

    override def contains(ev: CreatedEvent): Boolean =
      templateFilters.get(ev.getTemplateId).exists(evPredicate => evPredicate(ev)) ||
        // TODO (M3-18) Avoid linear search once we have proper interface support in multi-domain.
        interfaceFilters.exists { case (_, (evPredicate, _)) => evPredicate(ev) }

    override def mightContain[TC, TCid, T](
        templateCompanion: ContractCompanion[TC, TCid, T]
    ): Boolean =
      templateFilters.contains(templateCompanion.TEMPLATE_ID)

    override def mightContain[I, Id, View](
        interfaceCompanion: InterfaceCompanion[I, Id, View]
    ): Boolean =
      interfaceFilters.contains(interfaceCompanion.TEMPLATE_ID)

    override def decodeInterface[I, Id <: ContractId[I], View <: DamlRecord[_]](
        interfaceCompanion: InterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): Option[JavaContract[Id, View]] =
      interfaceFilters.get(interfaceCompanion.TEMPLATE_ID).flatMap { case (_, decoder) =>
        decoder.fromCreatedEvent(interfaceCompanion)(ev)
      }
  }

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      templateCompanion: ContractCompanion[TC, TCid, T]
  )(
      p: JavaContract[TCid, T] => Boolean
  ): (Identifier, CreatedEvent => Boolean) =
    (
      templateCompanion.TEMPLATE_ID,
      ev => p(JavaContract.fromCodegenContract[TCid, T](templateCompanion.fromCreatedEvent(ev))),
    )

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[I, Id <: ContractId[I], View <: DamlRecord[_]](
      interfaceCompanion: InterfaceCompanion[I, Id, View]
  )(
      p: JavaContract[Id, View] => Boolean,
      implementations: Seq[InterfaceImplementation[I, Id, View, _, _, _]],
  ): (Identifier, (CreatedEvent => Boolean, InterfaceDecoder)) = {
    val decoder: InterfaceDecoder = new InterfaceDecoder {

      val implementationViews: Map[Identifier, CreatedEvent => JavaContract[Id, View]] =
        implementations.map { i =>
          i.companion.TEMPLATE_ID -> i.toInterfaceContract(interfaceCompanion)
        }.toMap

      // the pattern : interfaceCompanion.type is sufficient proof that
      // Id=Id_ and View=View_ because companions are singleton values, and
      // : *.type is checked with `eq`, see SLS 8.2
      @nowarn("msg=cannot be checked at runtime")
      override def fromCreatedEvent[I_, Id_ <: ContractId[I_], View_ <: DamlRecord[_]](
          companion: InterfaceCompanion[I_, Id_, View_]
      )(ev: CreatedEvent): Option[JavaContract[Id_, View_]] = companion match {
        case _: interfaceCompanion.type =>
          implementationViews
            .get(ev.getTemplateId)
            .map(toIface => toIface(ev))
        case _ =>
          throw new IllegalArgumentException(
            s"Tried to decode ${companion.TEMPLATE_ID} but decoder is for ${interfaceCompanion.TEMPLATE_ID}"
          )
      }
    }
    (
      interfaceCompanion.TEMPLATE_ID,
      (
        (ev: CreatedEvent) =>
          decoder.fromCreatedEvent(interfaceCompanion)(ev).map(p).getOrElse(false),
        decoder,
      ),
    )
  }

  /** A smaller version of [[TransactionFilter]], only powerful enough for
    * intended [[AcsStore]] ingestion.
    */
  final case class IngestionFilter(
      primaryParty: PartyId,
      templateIds: Set[Identifier],
      interfaceIds: Set[Identifier],
  ) {
    def toTransactionFilter: TransactionFilter = {
      val interfaceIdsJava =
        interfaceIds.view.map(i => i -> Filter.Interface.INCLUDE_VIEW).toMap.asJava
      val partyString: String = primaryParty.toProtoPrimitive
      new FiltersByParty(
        Map[String, Filter](
          partyString -> new InclusiveFilter(templateIds.asJava, interfaceIdsJava)
        ).asJava
      )
    }
  }
}
