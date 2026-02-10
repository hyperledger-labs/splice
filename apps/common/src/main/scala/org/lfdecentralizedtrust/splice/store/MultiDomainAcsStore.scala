// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.NonEmptyList
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import com.daml.ledger.api.v2.transaction_filter.{CumulativeFilter, EventFormat}
import org.lfdecentralizedtrust.splice.util.Contract.Companion.{
  Interface,
  Template as TemplateCompanion,
}
import com.daml.ledger.javaapi.data.{CreatedEvent, ExercisedEvent, Identifier, Template}
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.metrics.api.MetricsContext
import org.lfdecentralizedtrust.splice.automation.MultiDomainExpiredContractTrigger.ListExpiredContracts
import org.lfdecentralizedtrust.splice.environment.ledger.api.{
  ActiveContract,
  IncompleteReassignmentEvent,
  ReassignmentEvent,
  TreeUpdateOrOffsetCheckpoint,
}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.HasIngestionSink
import org.lfdecentralizedtrust.splice.store.db.{AcsInterfaceViewRowData, AcsJdbcTypes, AcsRowData}
import org.lfdecentralizedtrust.splice.util.Contract.Companion
import org.lfdecentralizedtrust.splice.util.{
  AssignedContract,
  Contract,
  ContractWithState,
  PackageQualifiedName,
  QualifiedName,
  TemplateJsonDecoder,
}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.daml.nonempty.NonEmpty
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.{ErrorLoggingContext, NamedLogging}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.google.common.annotations.VisibleForTesting
import com.google.protobuf.ByteString
import io.circe.Json
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection
import org.lfdecentralizedtrust.splice.store.UpdateHistory.UpdateHistoryResponse

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

trait MultiDomainAcsStore extends HasIngestionSink with AutoCloseable with NamedLogging {
  protected def storeName: String
  def storeParty: PartyId

  protected implicit lazy val mc: MetricsContext = MetricsContext(
    "store_name" -> storeName,
    "store_party" -> storeParty.toString, // using .toString for historical reasons
  )
  protected def metricsFactory: LabeledMetricsFactory

  protected def metrics: StoreMetrics

  import MultiDomainAcsStore.*

  def lookupContractById[C, TCid <: ContractId[?], T](
      companion: C
  )(id: ContractId[?])(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]]

  /** Returns any contract of the same template as the passed companion.
    */
  def findAnyContractWithOffset[C, TCid <: ContractId[?], T](
      companion: C
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]]

  /** Check if the contract is active on the current domain.
    */
  def lookupContractByIdOnDomain[C, TCid <: ContractId[?], T](
      companion: C
  )(domain: SynchronizerId, id: ContractId[?])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[Contract[TCid, T]]] = lookupContractById(companion)(id).map(_.flatMap { c =>
    if (c.state == ContractState.Assigned(domain)) {
      Some(c.contract)
    } else {
      None
    }
  })

  /** Like `#lookupContractById` but only returns the [[ContractState]], not the
    * full decoded contract.
    */
  def lookupContractStateById(id: ContractId[?])(implicit
      traceContext: TraceContext
  ): Future[Option[ContractState]]

  /** True if the ids contains an id that has been archived. */
  def containsArchived(ids: Seq[ContractId[?]])(implicit
      traceContext: TraceContext
  ): Future[Boolean]

  /** Like `lookupContractById` but
    *
    * Throws [[Status.NOT_FOUND]] if no such contract exists.
    */
  final def getContractById[C, TCid <: ContractId[?], T](
      companion: C
  )(id: ContractId[?])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[ContractWithState[TCid, T]] =
    orContractIdNotFound(lookupContractById(companion)(id))(companion, id)

  /** Like `lookupContractByIdOnDomain` but
    *
    * Throws [[Status.NOT_FOUND]] if no such contract exists.
    */
  final def getContractByIdOnDomain[C, TCid <: ContractId[?], T](
      companion: C
  )(domain: SynchronizerId, id: ContractId[?])(implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Contract[TCid, T]] =
    orContractIdNotFound(lookupContractByIdOnDomain(companion)(domain, id))(companion, id)

  def listContractsPaginated[C, TCid <: ContractId[?], T](
      companion: C,
      after: Option[Long],
      limit: Limit,
      sortOrder: SortOrder,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[ResultsPage[ContractWithState[TCid, T]]]

  def listContracts[C, TCid <: ContractId[?], T](
      companion: C,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]]

  def listAssignedContracts[C, TCid <: ContractId[?], T](
      companion: C,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[AssignedContract[TCid, T]]]

  /** Only contracts with state ContractState.Assigned(domain) are considered
    * so contracts are omitted if they have been transferred or are in-flight.
    * This should generally only be used
    * for contracts that exist in per-domain variations and are never transferred, e.g.,
    * install contracts.
    */
  def listContractsOnDomain[C, TCid <: ContractId[?], T](
      companion: C,
      domain: SynchronizerId,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[Contract[TCid, T]]]

  private[splice] def listExpiredFromPayloadExpiry[C, TCid <: ContractId[T], T <: Template](
      companion: C
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): ListExpiredContracts[TCid, T]

  /** Stream all ready contracts that can be acted upon.
    * Note that the same contract can be returned multiple
    * times as it moves across domains.
    */
  def streamAssignedContracts[C, TCid <: ContractId[?], T](
      companion: C
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Source[AssignedContract[TCid, T], NotUsed]

  /** Stream all unassign events that are ready for assign.
    * The only guarantee provided is that a unassign that does not get transferred in
    * will eventually appear on the stream.
    */
  def streamReadyForAssign()(implicit
      tc: TraceContext
  ): Source[ReassignmentEvent.Unassign, NotUsed]

  /** Returns true if the unassign event can still potentially be transferred in.
    * Intended to be used as a staleness check for the results of `streamReadyForAssign`.
    */
  def isReadyForAssign(contractId: ContractId[?], out: ReassignmentId)(implicit
      tc: TraceContext
  ): Future[Boolean]

  def findInterfaceViewByContractId[C, ICid <: ContractId[?], View <: DamlRecord[View]](
      companion: C
  )(id: ICid)(implicit
      companionClass: ContractCompanion[C, ICid, View],
      tc: TraceContext,
  ): Future[Option[ContractWithState[ICid, View]]]

  def listInterfaceViews[C, ICid <: ContractId[?], View <: DamlRecord[View]](
      companion: C,
      limit: Limit = Limit.DefaultLimit,
  )(implicit
      companionClass: ContractCompanion[C, ICid, View],
      tc: TraceContext,
  ): Future[Seq[Contract[ICid, View]]]

  /** Signal when the store has finished ingesting ledger data from the given offset
    * or a larger one or node-level shutdown was initiated
    */
  def signalWhenIngestedOrShutdown(offset: Long)(implicit
      tc: TraceContext
  ): Future[Unit] = {
    metrics.signalWhenIngestedLatency.timeFuture(signalWhenIngestedOrShutdownImpl(offset))
  }

  protected def signalWhenIngestedOrShutdownImpl(offset: Long)(implicit
      tc: TraceContext
  ): Future[Unit]

  def ingestionSink: MultiDomainAcsStore.IngestionSink

  /** Testing API: returns all contracts that have in-flight reassignments */
  private[store] def listIncompleteReassignments()(implicit
      tc: TraceContext
  ): Future[Map[ContractId[?], NonEmpty[Set[ReassignmentId]]]]

  /** Testing API: lookup last ingested offset */
  private[store] def lookupLastIngestedOffset()(implicit tc: TraceContext): Future[Option[Long]]

  def initializeTxLogBackfilling()(implicit tc: TraceContext): Future[Unit]

  def getTxLogBackfillingState()(implicit
      tc: TraceContext
  ): Future[TxLogBackfillingState]

  def destinationHistory: HistoryBackfilling.DestinationHistory[UpdateHistoryResponse]
}

object MultiDomainAcsStore extends StoreErrors {

  sealed trait TxLogBackfillingState
  object TxLogBackfillingState {
    case object Complete extends TxLogBackfillingState
    case object InProgress extends TxLogBackfillingState
    case object NotInitialized extends TxLogBackfillingState
  }

  trait HasIngestionSink {
    def ingestionSink: MultiDomainAcsStore.IngestionSink
  }

  /** Static specification of a set of create events in scope for ingestion into an MultiDomainAcsStore. */
  trait ContractFilter[R <: AcsRowData, IR <: AcsInterfaceViewRowData] {

    /** The filter required for ingestion into this store. */
    def ingestionFilter: IngestionFilter

    /** Whether the event is in scope. */
    def contains(ev: CreatedEvent)(implicit elc: ErrorLoggingContext): Boolean

    /** Whether the contract referenced by the (archive)-ExercisedEvent should be archived.
      * Since the payload is not included in the event, this will be best-effort,
      * meaning that `true` might be returned for contracts that were never ingested.
      * This is fine, as removing something that doesn't exist is a noop.
      */
    def shouldArchive(exercisedEvent: ExercisedEvent): Boolean

    def matchingContractToRow(
        ev: CreatedEvent
    ): Option[R]

    def matchingInterfaceRows(
        ev: CreatedEvent
    )(implicit
        elc: ErrorLoggingContext
    ): Option[(AcsRowData.AcsRowDataFromInterface, Seq[IR])]

    def isStakeholderOf(ev: CreatedEvent): Boolean

    def ensureStakeholderOf(ev: CreatedEvent): Unit = {
      if (!isStakeholderOf(ev)) {
        // We decided to crash the store when we see an CreatedEvent that store party is not a stakeholder of it. Discussion in #6527
        throw new IllegalStateException(
          s"Cannot ingest the CreatedEvent as the store party is not a stakeholder of the contract. Crashing... : $ev"
        )
      }
    }

    def getAcsIndexColumnNames: Seq[String]
    def getInterfaceViewsIndexColumnNames: Seq[String]
  }

  private type DecodeFromCreatedEvent[TCid <: ContractId[T], T <: Template] =
    CreatedEvent => Option[Contract[TCid, T]]
  private type EncodeToRow[TCid <: ContractId[T], T <: Template, R <: AcsRowData] =
    Contract[TCid, T] => R

  case class TemplateFilter[TCid <: ContractId[T], T <: Template, R <: AcsRowData](
      evPredicate: CreatedEvent => Boolean,
      decodeFromCreatedEvent: DecodeFromCreatedEvent[TCid, T],
      encodeToRow: EncodeToRow[TCid, T, R],
  ) {
    def matchingContractToRow(
        ev: CreatedEvent
    ): Option[R] = {
      decodeFromCreatedEvent(ev).map(encodeToRow)
    }
  }

  private type DecodeInterfaceFromCreatedEvent[ICid, View] =
    CreatedEvent => Option[Contract[ICid, View]]
  private type EncodeInterfaceToRow[ICid, View, IR <: AcsInterfaceViewRowData] =
    Contract[ICid, View] => IR

  case class InterfaceFilter[ICid <: ContractId[Marker], Marker, View <: DamlRecord[
    ?
  ], IR <: AcsInterfaceViewRowData](
      interfaceId: Identifier,
      evPredicate: CreatedEvent => Boolean,
      decodeFromCreatedEvent: DecodeInterfaceFromCreatedEvent[ICid, View],
      encodeToRow: EncodeInterfaceToRow[ICid, View, IR],
  ) {
    def matchingContractToRow(ev: CreatedEvent): Option[IR] = {
      decodeFromCreatedEvent(ev).map(encodeToRow)
    }
  }

  /** A helper to easily construct a [[ContractFilter]] for a single party. */
  case class SimpleContractFilter[R <: AcsRowData, IR <: AcsInterfaceViewRowData](
      primaryParty: PartyId,
      templateFilters: Map[
        PackageQualifiedName,
        TemplateFilter[?, ?, R],
      ],
      interfaceFilters: Map[
        Identifier, // interfaces are not (currently) upgradeable, so we match by package-id
        InterfaceFilter[?, ?, ?, IR],
      ],
  )(implicit
      hasAcsIndexColumns: AcsRowData.HasIndexColumns[R],
      hasInterfaceViewsIndexColumns: AcsRowData.HasIndexColumns[IR],
  ) extends ContractFilter[R, IR] {

    override val ingestionFilter =
      IngestionFilter(
        primaryParty,
        // In interface filters the ledger API warns when using a package id so we convert to a package name here.
        interfaceFilters.keys.map(PackageQualifiedName.getFromResources(_)).toSeq,
      )

    def getAcsIndexColumnNames: Seq[String] = hasAcsIndexColumns.indexColumnNames
    def getInterfaceViewsIndexColumnNames: Seq[String] =
      hasInterfaceViewsIndexColumns.indexColumnNames

    override def contains(ev: CreatedEvent)(implicit elc: ErrorLoggingContext): Boolean = {
      val matchesTemplate = templateFilters
        .get(PackageQualifiedName.fromEvent(ev))
        .exists(_.evPredicate(ev))
      lazy val interfaceViews = ev.getInterfaceViews.asScala.filter { case (identifier, _) =>
        interfaceFilters.get(identifier).exists(_.evPredicate(ev))
      }
      lazy val interfaceToFailureMap = ev.getFailedInterfaceViews.asScala.filter {
        case (identifier, _) =>
          interfaceFilters.contains(identifier)
      }
      if (interfaceToFailureMap.nonEmpty) {
        elc.error(
          show"Found failed interface views that match an interface id in a filter: $interfaceToFailureMap. " +
            show"This might be a bug in the daml definition of the interface's view. " +
            show"Resolve the error, and if required, reingest the data."
        )
      }
      matchesTemplate || interfaceViews.nonEmpty
    }

    override def shouldArchive(
        exercisedEvent: ExercisedEvent
    ): Boolean = {
      val packageIdQualifiedName = PackageQualifiedName.fromEvent(exercisedEvent)
      templateFilters.contains(
        packageIdQualifiedName
      ) || exercisedEvent.getImplementedInterfaces.asScala.exists(interfaceId =>
        interfaceFilters.contains(interfaceId)
      )
    }

    override def matchingContractToRow(
        ev: CreatedEvent
    ): Option[R] = {
      for {
        templateFilter <- templateFilters.get(PackageQualifiedName.fromEvent(ev))
        row <- templateFilter.matchingContractToRow(ev)
      } yield row
    }

    override def matchingInterfaceRows(
        ev: CreatedEvent
    )(implicit
        elc: ErrorLoggingContext
    ): Option[(AcsRowData.AcsRowDataFromInterface, Seq[IR])] = {
      val acsRowData = AcsRowData.AcsRowDataFromInterface(
        ev.getTemplateId,
        new ContractId[DamlRecord[?]](ev.getContractId),
        AcsJdbcTypes.payloadJsonFromJavaApiDamlRecord(ev.getArguments),
        ev.getCreatedEventBlob,
        ev.getCreatedAt,
      )
      val interfaceRowDatas = for {
        (identifier, _) <- ev.getInterfaceViews.asScala
        interfaceFilter <- interfaceFilters.get(identifier)
        result <- interfaceFilter.matchingContractToRow(ev)
      } yield result

      if (interfaceRowDatas.isEmpty) {
        None
      } else {
        Some((acsRowData, interfaceRowDatas.toSeq))
      }
    }

    override def isStakeholderOf(ev: CreatedEvent): Boolean = {
      val eventStakeholder = (ev.getSignatories.asScala ++ ev.getObservers.asScala).toSet
      eventStakeholder.contains(primaryParty.toProtoPrimitive)
    }
  }
  object SimpleContractFilter {
    def apply[R <: AcsRowData](
        primaryParty: PartyId,
        templateFilters: Map[
          PackageQualifiedName,
          TemplateFilter[?, ?, R],
        ],
    )(implicit
        hasAcsIndexColumns: AcsRowData.HasIndexColumns[R]
    ): SimpleContractFilter[R, AcsInterfaceViewRowData.NoInterfacesIngested] =
      SimpleContractFilter[R, AcsInterfaceViewRowData.NoInterfacesIngested](
        primaryParty,
        templateFilters,
        Map.empty,
      )
  }

  /** Construct a contract filter for input into a [[SimpleContractFilter]]. */
  def mkFilter[TCid <: ContractId[T], T <: Template, R <: AcsRowData](
      templateCompanion: Contract.Companion.Template[TCid, T]
  )(
      p: Contract[TCid, T] => Boolean
  )(
      encode: EncodeToRow[TCid, T, R]
  ): (
      PackageQualifiedName,
      TemplateFilter[TCid, T, R],
  ) =
    (
      PackageQualifiedName.fromJavaCodegenCompanion(templateCompanion),
      TemplateFilter(
        ev => {
          val c = Contract.fromCreatedEvent(templateCompanion)(ev)
          c.exists(p)
        },
        ev => Contract.fromCreatedEvent(templateCompanion)(ev),
        encode,
      ),
    )

  def mkFilterInterface[ICid <: ContractId[Marker], Marker, View <: DamlRecord[
    View
  ], IR <: AcsInterfaceViewRowData](
      interfaceCompanion: Contract.Companion.Interface[ICid, Marker, View]
  )(p: Contract[ICid, View] => Boolean)(
      encode: EncodeInterfaceToRow[ICid, View, IR]
  ): (
      Identifier,
      InterfaceFilter[ICid, Marker, View, IR],
  ) =
    (
      interfaceCompanion.getTemplateIdWithPackageId,
      InterfaceFilter(
        interfaceCompanion.TEMPLATE_ID_WITH_PACKAGE_ID,
        ev => {
          val c = Contract.fromCreatedEvent(interfaceCompanion)(ev)
          c.exists(p)
        },
        ev => {
          val result = Contract.fromCreatedEvent(interfaceCompanion)(ev)
          result
        },
        encode,
      ),
    )

  /** A smaller version of [[TransactionFilter]], only powerful enough for
    * intended [[MultiDomainAcsStore]] ingestion.
    */
  final case class IngestionFilter(
      primaryParty: PartyId,
      includeInterfaces: Seq[PackageQualifiedName],
      includeCreatedEventBlob: Boolean = true,
  ) {

    def toEventFormat: EventFormat =
      EventFormat(
        filtersByParty = Map(
          primaryParty.toProtoPrimitive -> com.daml.ledger.api.v2.transaction_filter.Filters(
            CumulativeFilter(
              CumulativeFilter.IdentifierFilter.WildcardFilter(
                com.daml.ledger.api.v2.transaction_filter.WildcardFilter(includeCreatedEventBlob)
              )
            ) +: includeInterfaces.map { interfaceId =>
              CumulativeFilter(
                CumulativeFilter.IdentifierFilter.InterfaceFilter(
                  com.daml.ledger.api.v2.transaction_filter.InterfaceFilter(
                    Some(
                      com.daml.ledger.api.v2.value.Identifier(
                        packageId = s"#${interfaceId.packageName}",
                        moduleName = interfaceId.qualifiedName.moduleName,
                        entityName = interfaceId.qualifiedName.entityName,
                      )
                    ),
                    includeInterfaceView = true,
                    includeCreatedEventBlob = includeCreatedEventBlob,
                  )
                )
              )
            }
          )
        ),
        filtersForAnyParty = None,
      )
  }

  /** A query result computed as-of a specific set of per-domain ledger API offset. */
  final case class QueryResult[+A](
      offset: Long,
      value: A,
  ) {
    def map[B](f: A => B): QueryResult[B] = copy(value = f(value))

    def sequence[B](implicit B: A <:< Option[B]): Option[QueryResult[B]] =
      value.map(b => copy(value = b))
  }

  object QueryResult {
    implicit def prettyQueryResult[T <: PrettyPrinting]: Pretty[QueryResult[T]] = {
      import com.digitalasset.canton.logging.pretty.PrettyUtil.*
      prettyOfClass(
        param("offset", _.offset),
        param("value", _.value),
      )
    }
  }

  trait ContractCompanion[-C, TCid <: ContractId[?], T] {
    def fromCreatedEvent(
        companion: C
    )(
        event: CreatedEvent
    ): Option[Contract[TCid, T]]

    def fromJson(companion: C)(
        templateId: Identifier,
        contractId: String,
        payload: Json,
        createdEventBlob: ByteString,
        createdAt: Instant,
    )(implicit
        decoder: TemplateJsonDecoder
    ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
      val cId = toContractId(companion, contractId)
      fromJson(
        companion,
        cId,
        templateId,
        payload,
        createdEventBlob,
        createdAt,
      )
    }

    def typeId(companion: C): Identifier

    def packageQualifiedName(companion: C): PackageQualifiedName

    def toContractId(companion: C, contractId: String): TCid

    protected def fromJson(
        companion: C,
        cId: TCid,
        templateId: Identifier,
        payload: Json,
        createdEventBlob: ByteString,
        createdAt: Instant,
    )(implicit decoder: TemplateJsonDecoder): Either[ProtoDeserializationError, Contract[TCid, T]]
  }

  implicit def templateCompanion[TCid <: ContractId[T], T <: Template]
      : ContractCompanion[Contract.Companion.Template[TCid, T], TCid, T] =
    new ContractCompanion[Contract.Companion.Template[TCid, T], TCid, T] {
      override def fromCreatedEvent(companion: Contract.Companion.Template[TCid, T])(
          event: CreatedEvent
      ): Option[Contract[TCid, T]] = Contract.fromCreatedEvent(companion)(event)

      override def typeId(companion: Contract.Companion.Template[TCid, T]): Identifier =
        companion.getTemplateIdWithPackageId

      override def packageQualifiedName(
          companion: TemplateCompanion[TCid, T]
      ): PackageQualifiedName = PackageQualifiedName(
        companion.PACKAGE_NAME,
        QualifiedName(companion.getTemplateIdWithPackageId),
      )

      override def toContractId(companion: Companion.Template[TCid, T], contractId: String): TCid =
        companion.toContractId(new ContractId[T](contractId))

      override protected def fromJson(
          companion: Companion.Template[TCid, T],
          cId: TCid,
          templateId: Identifier,
          payload: Json,
          createdEventBlob: ByteString,
          createdAt: Instant,
      )(implicit
          decoder: TemplateJsonDecoder
      ): Either[ProtoDeserializationError, Contract[TCid, T]] = {
        Contract.fromHttp(typeId(companion), cId, decoder.decodeTemplate(companion))(
          templateId,
          payload,
          createdEventBlob,
          createdAt,
        )
      }
    }

  implicit def interfaceCompanion[ICid <: ContractId[Marker], Marker, View <: DamlRecord[View]]
      : ContractCompanion[Contract.Companion.Interface[ICid, Marker, View], ICid, View] =
    new ContractCompanion[Contract.Companion.Interface[ICid, Marker, View], ICid, View] {
      override def fromCreatedEvent(
          companion: Contract.Companion.Interface[ICid, Marker, View]
      )(event: CreatedEvent): Option[Contract[ICid, View]] =
        Contract.fromCreatedEvent(companion)(event)

      override def typeId(companion: Contract.Companion.Interface[ICid, Marker, View]): Identifier =
        companion.getTemplateIdWithPackageId

      override def packageQualifiedName(
          companion: Interface[ICid, Marker, View]
      ): PackageQualifiedName = PackageQualifiedName(
        companion.PACKAGE_NAME,
        QualifiedName(companion.getTemplateIdWithPackageId),
      )

      override def toContractId(
          companion: Companion.Interface[ICid, Marker, View],
          contractId: String,
      ): ICid = companion.toContractId(new ContractId[Marker](contractId))

      override protected def fromJson(
          companion: Companion.Interface[ICid, Marker, View],
          cId: ICid,
          templateId: Identifier,
          payload: Json,
          createdEventBlob: ByteString,
          createdAt: Instant,
      )(implicit
          decoder: TemplateJsonDecoder
      ): Either[ProtoDeserializationError, Contract[ICid, View]] = {
        Contract.fromHttp(typeId(companion), cId, decoder.decodeInterface(companion))(
          templateId,
          payload,
          createdEventBlob,
          createdAt,
        )
      }
    }

  import language.existentials

  /** The domain of [[MultiDomainAcsStore#listAssignedContractsNotOnDomainN]].
    * We'll have to take an implicit conversion approach instead to support
    * interfaces, but this is good enough for the current callers.
    */
  private[splice] type ConstrainedTemplate =
    TemplateCompanion[? <: ContractId[T], T] forSome {
      type T <: Template
    }

  final case class ReassignmentId(source: SynchronizerId, id: String)

  object ReassignmentId {
    def fromAssign(in: ReassignmentEvent.Assign) =
      ReassignmentId(in.source, in.unassignId)
    def fromUnassign(out: ReassignmentEvent.Unassign) =
      ReassignmentId(out.source, out.unassignId)
  }

  sealed abstract class ContractState extends PrettyPrinting with Product with Serializable {
    def isAssigned: Boolean

    def fold[Z](assigned: SynchronizerId => Z, inFlight: => Z): Z
  }

  object ContractState {
    case class Assigned(
        domain: SynchronizerId
    ) extends ContractState {
      override def pretty: Pretty[this.type] =
        prettyOfClass(param("domain", _.domain))
      override def isAssigned = true

      override def fold[Z](assigned: SynchronizerId => Z, inFlight: => Z) = assigned(domain)
    }

    case object InFlight extends ContractState {
      override def pretty: Pretty[this.type] = prettyOfObject[InFlight.type]
      override val isAssigned = false
      override def fold[Z](assigned: SynchronizerId => Z, inFlight: => Z) = inFlight
    }
  }

  trait IngestionSink {
    import IngestionSink.*

    def ingestionFilter: IngestionFilter

    /** Must be the first method called. Returns information about where and how to start ingestion. */
    def initialize()(implicit traceContext: TraceContext): Future[IngestionStart]

    @VisibleForTesting
    def ingestAcs(
        offset: Long,
        acs: Seq[ActiveContract],
        incompleteOut: Seq[IncompleteReassignmentEvent.Unassign],
        incompleteIn: Seq[IncompleteReassignmentEvent.Assign],
    )(implicit mat: Materializer, traceContext: TraceContext): Future[Unit] = {
      ingestAcsStreamInBatches(
        Source.single[Seq[BaseLedgerConnection.ActiveContractsItem]](
          acs.map[BaseLedgerConnection.ActiveContractsItem](
            BaseLedgerConnection.ActiveContractsItem.ActiveContract(_)
          ) ++
            incompleteOut.iterator.map(
              BaseLedgerConnection.ActiveContractsItem.IncompleteUnassign(_)
            ) ++
            incompleteIn.iterator.map(
              BaseLedgerConnection.ActiveContractsItem.IncompleteAssign(_)
            )
        ),
        offset,
      )
    }

    def ingestAcsStreamInBatches(
        source: Source[Seq[BaseLedgerConnection.ActiveContractsItem], NotUsed],
        offset: Long,
    )(implicit
        tc: TraceContext,
        mat: Materializer,
    ): Future[Unit]

    def ingestUpdateBatch(batch: NonEmptyList[TreeUpdateOrOffsetCheckpoint])(implicit
        traceContext: TraceContext
    ): Future[Unit]
  }

  object IngestionSink {
    sealed trait IngestionStart

    object IngestionStart {

      /** Ingestion service should ingest the ACS at an offset chosen by the service,
        * then resume ingesting updates from there
        */
      final case object InitializeAcsAtLatestOffset extends IngestionStart

      /** Only for UpdateHistory, not app stores!
        * Starts from the latest pruned offset under the assumptions:
        * - Scan: anything before it will be backfilled.
        * - Wallet: won't miss any updates, because all updates visible to the wallet party only appear
        *           after you onboard that party through the wallet app. Does not backfill.
        * Does not load any ACS.
        */
      final case object UpdateHistoryInitAtLatestPrunedOffset extends IngestionStart

      /** Ingestion service should ingest the ACS at the specified offset,
        * then resume ingesting updates from there
        */
      final case class InitializeAcsAtOffset(
          offset: Long
      ) extends IngestionStart

      /** Ingestion service should resume ingesting updates from the specified offset
        */
      final case class ResumeAtOffset(
          offset: Long
      ) extends IngestionStart
    }

  }

  // The state of a contract in the store. Note that, contrary to `ContractState`, this can
  // also include archived contracts.
  sealed abstract class StoreContractState extends PrettyPrinting {
    def toActiveState: Option[MultiDomainAcsStore.ContractState] =
      this match {
        case StoreContractState.Assigned(domain) =>
          Some(MultiDomainAcsStore.ContractState.Assigned(domain))
        case StoreContractState.InFlight(_) => Some(MultiDomainAcsStore.ContractState.InFlight)
        case StoreContractState.Archived => None
      }

  }
  object StoreContractState {

    /** Observed activation (assign/create).
      */
    final case class Assigned(domain: SynchronizerId) extends StoreContractState {
      override def pretty: Pretty[this.type] = prettyOfClass(
        param("domain", _.domain)
      )
    }

    /** Observed unassign but not assign.
      */
    final case class InFlight(out: ReassignmentEvent.Unassign) extends StoreContractState {
      override def pretty: Pretty[this.type] = prettyOfClass(
        param("out", _.out)
      )
    }

    /** Observed archive but there are still incomplete transfers.
      */
    final case object Archived extends StoreContractState {
      override def pretty: Pretty[this.type] = prettyOfObject[Archived.type]
    }
  }

  /** The "most recent" state for a contract where "most recent"
    * is defined based as the highest transfer counter
    */
  case class ContractStateEvent(
      contractId: ContractId[?],
      transferCounter: Long,
      state: StoreContractState,
  ) extends PrettyPrinting {

    def toAssigned: Option[SynchronizerId] = state match {
      case StoreContractState.Assigned(domain) => Some(domain)
      case StoreContractState.InFlight(_) | StoreContractState.Archived => None
    }

    override def pretty: Pretty[this.type] = prettyOfClass(
      param("contractId", _.contractId),
      param("transferCounter", _.transferCounter),
      param("state", _.state),
    )
  }

  private def orContractIdNotFound[A, C](found: Future[Option[A]])(companion: C, id: ContractId[?])(
      implicit
      ec: ExecutionContext,
      companionClass: ContractCompanion[C, ?, ?],
  ): Future[A] =
    found.map { result =>
      result.getOrElse(
        throw contractIdNotFound(PrettyContractId(companionClass.typeId(companion), id))
      )
    }

  /** Max batch size for domain reassignment automation.  Not a hard limit,
    * chosen to be "reasonable".
    */
  val notOnDomainsTotalLimit: PageLimit = PageLimit tryCreate 1000
}
