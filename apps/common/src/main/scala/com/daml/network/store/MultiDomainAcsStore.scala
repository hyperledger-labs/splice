package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.api.v1.{transaction_filter as scalaFilter}
import com.daml.ledger.javaapi.data.{
  ContractMetadata,
  CreatedEvent,
  Filter,
  FiltersByParty,
  Identifier,
  InclusiveFilter,
  NoFilter,
  Template,
  TransactionFilter,
}
import com.daml.ledger.javaapi.data.codegen.{
  ContractId,
  DamlRecord,
  ContractCompanion as JavaContractCompanion,
  InterfaceCompanion as JavaInterfaceCompanion,
}
import com.daml.network.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import com.daml.network.environment.ledger.api.{InFlightTransferOutEvent, TransferEvent, TreeUpdate}
import com.daml.network.util.Contract.Companion
import com.daml.network.util.Contract.Companion.Interface
import com.daml.network.util.{Contract, TemplateJsonDecoder}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.admin.api.client.data.TemplateId
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf
import com.google.protobuf.ByteString
import io.circe.Json
import io.grpc.Status

import java.time.Instant
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait MultiDomainAcsStore extends AutoCloseable {

  import MultiDomainAcsStore.*

  private val DefaultLimit: Long = 1000L

  def lookupContractById[C, TCid <: ContractId[_], T](
      companion: C
  )(id: ContractId[_])(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Option[ContractWithState[TCid, T]]]

  /** Get a contract by id.
    *
    * Throws [[Status.NOT_FOUND]] if no such contract exists.
    */
  def getContractById[C, TCid <: ContractId[_], T](
      companion: C
  )(id: ContractId[_])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
  ): Future[ContractWithState[TCid, T]] =
    lookupContractById(companion)(id).map(result =>
      result.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(
            show"contract id not found: ${PrettyContractId(companionClass.typeId(companion), id)}"
          )
          .asRuntimeException
      )
    )

  /** Check if the contract is active on the current domain.
    */
  def lookupContractByIdOnDomain[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, id: ContractId[_])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
  ): Future[Option[Contract[TCid, T]]] = lookupContractById(companion)(id).map(_.flatMap { c =>
    if (c.state == ContractState.Assigned(domain)) {
      Some(c.contract)
    } else {
      None
    }
  })

  // Variant of lookupContractByIdOnDomain that will fail the future
  // if the contract is active but not in state ContractState.Assigned(domain).
  // This is particularly useful in automation where this will ensure it retries until
  // all contracts have finished transferring.
  def lookupContractByIdOnDomainOrRetry[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, id: ContractId[_])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
  ): Future[Option[Contract[TCid, T]]] =
    lookupContractById(companion)(id).map(_.map { c =>
      if (c.state == ContractState.Assigned(domain)) {
        c.contract
      } else {
        throw Status.FAILED_PRECONDITION
          .withDescription(show"Contract $id is active but not on domain $domain: ${c.state}")
          .asRuntimeException()
      }
    })

  /** Like `lookupContractByIdOnDomain` but
    *
    * Throws [[Status.NOT_FOUND]] if no such contract exists.
    */
  def getContractByIdOnDomain[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, id: ContractId[_])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
  ): Future[Contract[TCid, T]] =
    lookupContractByIdOnDomain(companion)(domain, id).map(result =>
      result.getOrElse(
        throw Status.NOT_FOUND
          .withDescription(
            show"contract id not found: ${PrettyContractId(companionClass.typeId(companion), id)}"
          )
          .asRuntimeException
      )
    )

  def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
      limit: Long = DefaultLimit,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[ContractWithState[TCid, T]]]

  def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
      limit: Long = DefaultLimit,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[ReadyContract[TCid, T]]]

  /** Only contracts with state ContractState.Assigned(domain) are considered
    * so contracts are omitted if they have been transferred or are in-flight.
    * This should generally only be used
    * for contracts that exist in per-domain variations and are never transferred, e.g.,
    * install contracts.
    */
  def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: DomainId,
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
      limit: Long = DefaultLimit,
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[Contract[TCid, T]]]

  private[network] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[T], T <: Template](
      companion: C
  )(
      expiresAt: T => java.time.Instant
  )(implicit companionClass: ContractCompanion[C, TCid, T]): ListExpiredContracts[TCid, T] =
    (now, limit) =>
      _ =>
        listReadyContracts(
          companion = companion,
          filter = co => now.toInstant isAfter expiresAt(co.payload),
          limit = limit.toLong,
        )

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

  /** Signal when the store has finished ingesting ledger data from the given offset for the given domain
    * or a larger one or node-level shutdown was initiated
    */
  def signalWhenIngestedOrShutdown(domainId: DomainId, offset: String)(implicit
      tc: TraceContext
  ): Future[Unit]

  /** Signal when the store has finished ingesting ledger data for the ACS of the given domain
    * or node-level shutdown was initiated
    */
  def signalWhenAcsCompletedOrShutdown(domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Unit]

  def ingestionSink: MultiDomainAcsStore.IngestionSink
}

object MultiDomainAcsStore {

  // TODO (#2676) Remove the hacky interface decoding machinery once we have proper interface support for multi-domain.
  abstract class InterfaceDecoder {
    def fromCreatedEvent[I, Id <: ContractId[I], View <: DamlRecord[_]](
        companion: JavaInterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): Option[Contract[Id, View]]
  }

  final case class InterfaceImplementation[I, Id <: ContractId[I], View <: DamlRecord[
    _
  ], TCid <: ContractId[Tmpl], Tmpl <: Template](
      companion: Contract.Companion.Template[TCid, Tmpl],
      view: Tmpl => View,
  ) {
    def toInterfaceContract(
        interfaceCompanion: JavaInterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): Option[Contract[Id, View]] = {
      val templateContractO: Option[Contract[TCid, Tmpl]] = Contract.fromCreatedEvent(companion)(ev)
      templateContractO.map(templateContract =>
        Contract[Id, View](
          interfaceCompanion.TEMPLATE_ID,
          interfaceCompanion.toContractId(new ContractId(templateContract.contractId.contractId)),
          view(templateContract.payload),
          ev.getContractMetadata,
          ev.getCreateArgumentsBlob,
        )
      )
    }
  }

  object InterfaceImplementation {
    def apply[I, Id <: ContractId[I], View <: DamlRecord[_], TCid <: ContractId[
      Tmpl
    ], Tmpl <: Template](
        companion: Contract.Companion.Template[TCid, Tmpl]
    ): (Tmpl => View) => InterfaceImplementation[I, Id, View, TCid, Tmpl] =
      view => InterfaceImplementation(companion, view)
  }

  /** Static specification of a set of create events in scope for ingestion into an MultiDomainAcsStore. */
  trait ContractFilter {

    /** The filter required for ingestion into this store. */
    def ingestionFilter: IngestionFilter

    /** Whether the event is in scope. */
    def contains(ev: CreatedEvent): Boolean

    /** Whether the scope might contain an event of the given template. */
    def mightContain[TC, TCid, T](templateCompanion: JavaContractCompanion[TC, TCid, T]): Boolean

    /** Whether the scope might contain an event of the given interface. */
    def mightContain[I, Id, View](interfaceCompanion: JavaInterfaceCompanion[I, Id, View]): Boolean

    def decodeInterface[I, Id <: ContractId[I], View <: DamlRecord[_]](
        interfaceCompanion: JavaInterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): Option[Contract[Id, View]]
  }

  /** A helper to easily construct a [[ContractFilter]] for a single party. */
  case class SimpleContractFilter(
      primaryParty: PartyId,
      templateFilters: Map[Identifier, CreatedEvent => Boolean],
      interfaceFilters: Map[Identifier, (CreatedEvent => Boolean, InterfaceDecoder)] = Map.empty,
  ) extends ContractFilter {

    override val ingestionFilter =
      IngestionFilter(
        primaryParty,
        templateIds = templateFilters.keySet,
        interfaceIds = interfaceFilters.keySet,
      )

    override def contains(ev: CreatedEvent): Boolean =
      templateFilters.get(ev.getTemplateId).exists(evPredicate => evPredicate(ev)) ||
        // TODO (#2676) Avoid linear search once we have proper interface support in multi-domain.
        interfaceFilters.exists { case (_, (evPredicate, _)) => evPredicate(ev) }

    override def mightContain[TC, TCid, T](
        templateCompanion: JavaContractCompanion[TC, TCid, T]
    ): Boolean =
      templateFilters.contains(templateCompanion.TEMPLATE_ID)

    override def mightContain[I, Id, View](
        interfaceCompanion: JavaInterfaceCompanion[I, Id, View]
    ): Boolean =
      interfaceFilters.contains(interfaceCompanion.TEMPLATE_ID)

    override def decodeInterface[I, Id <: ContractId[I], View <: DamlRecord[_]](
        interfaceCompanion: JavaInterfaceCompanion[I, Id, View]
    )(ev: CreatedEvent): Option[Contract[Id, View]] =
      interfaceFilters.get(interfaceCompanion.TEMPLATE_ID).flatMap { case (_, decoder) =>
        decoder.fromCreatedEvent(interfaceCompanion)(ev)
      }
  }

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[TCid <: ContractId[T], T <: Template](
      templateCompanion: Contract.Companion.Template[TCid, T]
  )(
      p: Contract[TCid, T] => Boolean
  ): (Identifier, CreatedEvent => Boolean) =
    (
      templateCompanion.TEMPLATE_ID,
      ev => Contract.fromCreatedEvent(templateCompanion)(ev).exists(p),
    )

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[I, Id <: ContractId[I], View <: DamlRecord[_]](
      interfaceCompanion: JavaInterfaceCompanion[I, Id, View]
  )(
      p: Contract[Id, View] => Boolean,
      implementations: Seq[InterfaceImplementation[I, Id, View, _, _]],
  ): (Identifier, (CreatedEvent => Boolean, InterfaceDecoder)) = {
    val decoder: InterfaceDecoder = new InterfaceDecoder {

      val implementationViews: Map[Identifier, CreatedEvent => Option[Contract[Id, View]]] =
        implementations.map { i =>
          i.companion.TEMPLATE_ID -> i.toInterfaceContract(interfaceCompanion)
        }.toMap

      // the pattern : interfaceCompanion.type is sufficient proof that
      // Id=Id_ and View=View_ because companions are singleton values, and
      // : *.type is checked with `eq`, see SLS 8.2
      @nowarn("msg=cannot be checked at runtime")
      override def fromCreatedEvent[I_, Id_ <: ContractId[I_], View_ <: DamlRecord[_]](
          companion: JavaInterfaceCompanion[I_, Id_, View_]
      )(ev: CreatedEvent): Option[Contract[Id_, View_]] = companion match {
        case _: interfaceCompanion.type =>
          implementationViews
            .get(ev.getTemplateId)
            .flatMap(toIface => toIface(ev))
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
    * intended [[MultiDomainAcsStore]] ingestion.
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

    // TODO (#3956) callers should use `toTransactionFilter` instead when
    // snapshotsvc supports real filters
    def toTransactionFilterAllContracts: TransactionFilter = {
      val partyString: String = primaryParty.toProtoPrimitive
      new FiltersByParty(Map[String, Filter](partyString -> NoFilter.instance).asJava)
    }

    // TODO (#3956) callers should use `toTransactionFilter` instead when
    // snapshotsvc supports real filters
    def toTransactionFilterAllContractsScala: scalaFilter.TransactionFilter =
      scalaFilter.TransactionFilter(
        Map(primaryParty.toProtoPrimitive -> scalaFilter.Filters())
      )
  }

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

  object QueryResult {
    implicit def prettyQueryResult[T <: PrettyPrinting]: Pretty[QueryResult[T]] = {
      import com.digitalasset.canton.logging.pretty.PrettyUtil.*
      prettyOfClass(
        param("offsets", _.offsets.map({ case (k, v) => k -> v.unquoted })),
        param("value", _.value),
      )
    }
  }

  trait ContractCompanion[-C, TCid <: ContractId[_], T] {
    def fromCreatedEvent(
        companion: C
    )(filter: MultiDomainAcsStore.ContractFilter, event: CreatedEvent): Option[Contract[TCid, T]]

    def fromJson(companion: C)(
        templateId: TemplateId,
        contractId: String,
        payload: Json,
        createdAt: Instant,
        contractKeyHash: Option[String],
        driverInternal: Array[Byte],
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
      val cId = toContractId(companion, contractId)
      val javaTemplateId =
        new Identifier(templateId.packageId, templateId.moduleName, templateId.entityName)
      val metadata = new ContractMetadata(
        createdAt,
        ByteString.copyFromUtf8(contractKeyHash.getOrElse("")),
        ByteString.copyFrom(driverInternal),
      )
      fromJson(
        companion,
        cId,
        javaTemplateId,
        payload,
        metadata,
        createArgumentsBlob =
          com.google.protobuf.Any.getDefaultInstance, // TODO (#5012): this shouldn't be empty to support interfaces
      )
    }

    def mightContain(filter: MultiDomainAcsStore.ContractFilter)(companion: C): Boolean

    def typeId(companion: C): Identifier

    def toContractId(companion: C, contractId: String): TCid

    protected def fromJson(
        companion: C,
        cId: TCid,
        templateId: Identifier,
        payload: Json,
        metadata: ContractMetadata,
        createArgumentsBlob: com.google.protobuf.Any,
    )(implicit decoder: TemplateJsonDecoder): Either[ProtoDeserializationError, Contract[TCid, T]]
  }

  implicit def templateCompanion[TCid <: ContractId[T], T <: Template]
      : ContractCompanion[Contract.Companion.Template[TCid, T], TCid, T] =
    new ContractCompanion[Contract.Companion.Template[TCid, T], TCid, T] {
      override def fromCreatedEvent(companion: Contract.Companion.Template[TCid, T])(
          filter: MultiDomainAcsStore.ContractFilter,
          event: CreatedEvent,
      ): Option[Contract[TCid, T]] = Contract.fromCreatedEvent(companion)(event)

      override def mightContain(filter: MultiDomainAcsStore.ContractFilter)(
          companion: Contract.Companion.Template[TCid, T]
      ): Boolean = filter.mightContain(companion)

      override def typeId(companion: Contract.Companion.Template[TCid, T]): Identifier =
        companion.TEMPLATE_ID

      override def toContractId(companion: Companion.Template[TCid, T], contractId: String): TCid =
        companion.toContractId(new ContractId[T](contractId))

      override protected def fromJson(
          companion: Companion.Template[TCid, T],
          cId: TCid,
          templateId: Identifier,
          payload: Json,
          metadata: ContractMetadata,
          createArgumentsBlob: protobuf.Any,
      )(implicit
          decoder: TemplateJsonDecoder
      ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
        Contract.fromJson(typeId(companion), cId, decoder.decodeTemplate(companion))(
          templateId,
          payload,
          metadata,
          createArgumentsBlob,
        )
      }
    }

  implicit def interfaceCompanion[I, Id <: ContractId[I], View <: DamlRecord[_]]
      : ContractCompanion[Contract.Companion.Interface[Id, I, View], Id, View] =
    new ContractCompanion[Contract.Companion.Interface[Id, I, View], Id, View] {
      override def fromCreatedEvent(companion: Contract.Companion.Interface[Id, I, View])(
          filter: MultiDomainAcsStore.ContractFilter,
          event: CreatedEvent,
      ): Option[Contract[Id, View]] =
        filter.decodeInterface(companion)(event)

      override def mightContain(filter: MultiDomainAcsStore.ContractFilter)(
          companion: Contract.Companion.Interface[Id, I, View]
      ): Boolean = filter.mightContain(companion)

      override def typeId(companion: Contract.Companion.Interface[Id, I, View]): Identifier =
        companion.TEMPLATE_ID

      override def toContractId(companion: Interface[Id, I, View], contractId: String): Id =
        companion.toContractId(new ContractId[I](contractId))

      override protected def fromJson(
          companion: Interface[Id, I, View],
          cId: Id,
          templateId: Identifier,
          payload: Json,
          metadata: ContractMetadata,
          createArgumentsBlob: protobuf.Any,
      )(implicit
          decoder: TemplateJsonDecoder
      ): Either[ProtoDeserializationError, Contract[Id, View]] = {
        Contract.fromJson(typeId(companion), cId, decoder.decodeInterface(companion))(
          templateId,
          payload,
          metadata,
          createArgumentsBlob,
        )
      }
    }

  final case class TransferId(source: DomainId, id: String)

  object TransferId {
    def fromTransferIn(in: TransferEvent.In) =
      TransferId(in.source, in.transferOutId)
    def fromTransferOut(out: TransferEvent.Out) =
      TransferId(out.source, out.transferOutId)
  }

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
  ) {
    def toReadyContract: Option[ReadyContract[TCid, T]] =
      state match {
        case ContractState.Assigned(domain) => Some(ReadyContract(contract, domain))
        case ContractState.InFlight => None
      }
  }

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
        inFlight: Seq[InFlightTransferOutEvent],
    )(implicit traceContext: TraceContext): Future[Unit]

    def ingestOffset(domain: DomainId, offset: String)(implicit
        traceContext: TraceContext
    ): Future[Unit]

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
