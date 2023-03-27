package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{TreeUpdate, TransferEvent}
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.ledger.javaapi.data.{CreatedEvent, Identifier, Template}
import com.daml.network.util.Contract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import scala.concurrent.{ExecutionContext, Future}

trait MultiDomainAcsStore extends AutoCloseable {

  import MultiDomainAcsStore.*

  def lookupContractById[C, TCid <: ContractId[_], T](
      companion: C
  )(id: ContractId[_])(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Option[ContractWithState[TCid, T]]]

  /** Variant of lookupContractById that will fail the future
    * if the contract is active but not in state ContractState.Assigned(domain).
    * This is particularly useful in automation where this will ensure it retries until
    * all contracts have finished transferring.
    */
  def lookupContractByIdOnDomain[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, id: ContractId[_])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
  ): Future[Option[Contract[TCid, T]]] = lookupContractById(companion)(id).map(_.map { c =>
    if (c.state == ContractState.Assigned(domain)) {
      c.contract
    } else {
      throw Status.FAILED_PRECONDITION
        .withDescription(show"Contract $id is active but not on domain $domain: ${c.state}")
        .asRuntimeException()
    }
  })

  /** Find a contract that satisfies a predicate.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContractWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(
      p: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]]

  /** Find a contract that satisfies a predicate on the given domain.
    * Only contracts with state ContractState.Assigned(domain) are considered
    * so contracts are omitted if they have been transferred or are in-flight.
    * This should generally only be used
    * for contracts that exist in per-domain variations and are never transferred, e.g.,
    * install contracts.
    *
    * Caution: this function traverses all contracts!
    * Not intended for production use, but very useful for prototyping.
    */
  def findContractOnDomainWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(
      domain: DomainId,
      p: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[Contract[TCid, T]]]]

  def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
      limit: Option[Long] = None,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[ContractWithState[TCid, T]]]

  /** Stream all ready contracts that can be acted upon.
    * Note that the same contract can be returned multiple
    * times as it moves across domains.
    */
  def streamReadyContracts[C, TCid <: ContractId[_], T](
      companion: C
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Source[ReadyContract[TCid, T], NotUsed]

  /** Stream all transfer out events that are ready for transfer in.
    * The only guarantee provided is that a transfer out that does not get transferred in
    * will eventually appear on the stream.
    */
  def streamReadyForTransferIn(): Source[TransferEvent.Out, NotUsed]

  /** Returns true if the transfer out event can still potentially be transferred in.
    * Intended to be used as a staleness check for the results of `streamReadyForTransferIn`.
    */
  def isReadyForTransferIn(out: TransferId): Future[Boolean]

  def ingestionSink: MultiDomainAcsStore.IngestionSink
}

object MultiDomainAcsStore {

  /** A query result computed as-of a specific set of per-domain ledger API offset. */
  case class QueryResult[A](
      offsets: Map[DomainId, String],
      value: A,
  ) {
    // TODO(M3-19) Remove client-side minimum once the dedup APIs
    // allow passing in multiple offsets.
    def deduplicationOffset: String =
      // Throwing here will result in automation retrying which is exactly
      // what we want.
      offsets.values.minOption.getOrElse(
        throw Status.FAILED_PRECONDITION
          .withDescription("No data for any domain has been ingested")
          .asRuntimeException()
      )
  }

  trait ContractCompanion[-C, TCid <: ContractId[_], T] {
    def fromCreatedEvent(
        companion: C
    )(filter: AcsStore.ContractFilter, event: CreatedEvent): Option[Contract[TCid, T]]
    def mightContain(filter: AcsStore.ContractFilter)(companion: C): Boolean
    def typeId(companion: C): Identifier
  }

  implicit def templateCompanion[TCid <: ContractId[T], T <: Template]
      : ContractCompanion[Contract.Companion.Template[TCid, T], TCid, T] =
    new ContractCompanion[Contract.Companion.Template[TCid, T], TCid, T] {
      override def fromCreatedEvent(companion: Contract.Companion.Template[TCid, T])(
          filter: AcsStore.ContractFilter,
          event: CreatedEvent,
      ): Option[Contract[TCid, T]] = Contract.fromCreatedEvent(companion)(event)
      override def mightContain(filter: AcsStore.ContractFilter)(
          companion: Contract.Companion.Template[TCid, T]
      ): Boolean = filter.mightContain(companion)
      override def typeId(companion: Contract.Companion.Template[TCid, T]): Identifier =
        companion.TEMPLATE_ID
    }

  implicit def interfaceCompanion[I, Id <: ContractId[I], View <: DamlRecord[_]]
      : ContractCompanion[Contract.Companion.Interface[Id, I, View], Id, View] =
    new ContractCompanion[Contract.Companion.Interface[Id, I, View], Id, View] {
      override def fromCreatedEvent(companion: Contract.Companion.Interface[Id, I, View])(
          filter: AcsStore.ContractFilter,
          event: CreatedEvent,
      ): Option[Contract[Id, View]] =
        filter.decodeInterface(companion)(event)
      override def mightContain(filter: AcsStore.ContractFilter)(
          companion: Contract.Companion.Interface[Id, I, View]
      ): Boolean = filter.mightContain(companion)
      override def typeId(companion: Contract.Companion.Interface[Id, I, View]): Identifier =
        companion.TEMPLATE_ID
    }

  final case class TransferId(source: DomainId, id: String)

  object TransferId {
    def fromTransferIn(in: TransferEvent.In) =
      TransferId(in.source, in.transferOutId)
    def fromTransferOut(out: TransferEvent.Out) =
      TransferId(out.source, out.transferOutId)
  }

  import AcsStore.IngestionFilter

  /** A contract that is ready to be acted upon
    * on the given domain.
    */
  final case class ReadyContract[TCid, T](
      contract: Contract[TCid, T],
      domain: DomainId,
  ) extends PrettyPrinting {
    override def pretty = prettyOfClass[ReadyContract[TCid, T]](
      param("contract", _.contract),
      param("domain", _.domain),
    )
  }

  final case class ContractWithState[TCid, T](
      contract: Contract[TCid, T],
      state: ContractState,
  )

  sealed abstract class ContractState extends PrettyPrinting

  object ContractState {
    case class Assigned(
        domain: DomainId
    ) extends ContractState {
      override def pretty: Pretty[this.type] =
        prettyOfClass(param("domain", _.domain))
    }

    case object InFlight extends ContractState {
      override def pretty: Pretty[this.type] = prettyOfObject[InFlight.type]
    }
  }

  trait IngestionSink {

    def ingestionFilter: IngestionFilter

    def ingestAcsAndTransferOuts(
        domain: DomainId,
        acs: Seq[CreatedEvent],
        inFlight: Seq[TransferEvent.Out],
    )(implicit traceContext: TraceContext): Future[Unit]

    def switchToIngestingUpdates(
        domain: DomainId,
        offset: String,
    )(implicit traceContext: TraceContext): Future[Unit]

    def getLastIngestedOffset(domain: DomainId): Future[Option[String]]

    def ingestUpdate(domain: DomainId, transfer: TreeUpdate)(implicit
        traceContext: TraceContext
    ): Future[Unit]

    /** Called by automation if the transfer in fails because it has already been completed.
      * See docs on InMemoryMultiDomainAcsStore.State for details on bootstrapping.
      */
    def removeTransferOutIfBootstrap(cid: ContractId[_], transferId: TransferId)(implicit
        traceContext: TraceContext
    ): Future[Unit]
  }
}
