package com.daml.network.store

import com.daml.network.environment.LedgerClient.GetTreeUpdatesResponse.{TreeUpdate, TransferEvent}
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.ledger.javaapi.data.{CreatedEvent, Template}
import com.daml.network.util.Contract
import Contract.Companion.Template as TemplateCompanion
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait MultiDomainAcsStore extends AutoCloseable {

  import MultiDomainAcsStore.ContractWithState

  def lookupContractById[TCid <: ContractId[T], T <: Template](
      templateCompanion: TemplateCompanion[TCid, T]
  )(id: ContractId[T]): Future[Option[ContractWithState[TCid, T]]]

  def listContracts[TCid <: ContractId[T], T <: Template](
      templateCompanion: TemplateCompanion[TCid, T],
      filter: Contract[TCid, T] => Boolean = (_: Contract[TCid, T]) => true,
      limit: Option[Long] = None,
  ): Future[Seq[ContractWithState[TCid, T]]]

  def ingestionSink: MultiDomainAcsStore.IngestionSink
}

object MultiDomainAcsStore {

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
    def removeTransferOutIfBootstrap(transfer: TransferEvent.Out)(implicit
        traceContext: TraceContext
    ): Future[Unit]
  }
}
