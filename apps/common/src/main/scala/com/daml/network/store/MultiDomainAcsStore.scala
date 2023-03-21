package com.daml.network.store

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{TreeUpdate, TransferEvent}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{CreatedEvent, Template}
import com.daml.network.util.Contract
import Contract.Companion.Template as TemplateCompanion
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait MultiDomainAcsStore extends AutoCloseable {

  import MultiDomainAcsStore.{ContractWithState, TransferId}

  def lookupContractById[TCid <: ContractId[T], T <: Template](
      templateCompanion: TemplateCompanion[TCid, T]
  )(id: ContractId[T]): Future[Option[ContractWithState[TCid, T]]]

  def listContracts[TCid <: ContractId[T], T <: Template](
      templateCompanion: TemplateCompanion[TCid, T],
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
      limit: Option[Long] = None,
  ): Future[Seq[ContractWithState[TCid, T]]]

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

  case class TransferId(source: DomainId, id: String)

  object TransferId {
    def fromTransferIn(in: TransferEvent.In) =
      TransferId(in.source, in.transferOutId)
    def fromTransferOut(out: TransferEvent.Out) =
      TransferId(out.source, out.transferOutId)
  }

  import AcsStore.IngestionFilter

  case class ContractWithState[TCid, T](
      contract: Contract[TCid, T],
      state: ContractState,
  )

  sealed abstract class ContractState

  object ContractState {
    case class Assigned(
        domain: DomainId
    ) extends ContractState

    case object InFlight extends ContractState
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
