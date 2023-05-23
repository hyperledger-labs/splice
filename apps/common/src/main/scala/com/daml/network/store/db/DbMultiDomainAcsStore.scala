package com.daml.network.store.db

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.environment.RetryProvider
import com.daml.network.environment.ledger.api.TransferEvent
import com.daml.network.store.{MultiDomainAcsStore, TxLogStore}
import com.daml.network.util.Contract
import com.digitalasset.canton.concurrent.FutureSupervisor
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.tracing.TraceContext

import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}

class DbMultiDomainAcsStore[TXI <: TxLogStore.IndexRecord, TXE <: TxLogStore.Entry[TXI]](
    override protected val loggerFactory: NamedLoggerFactory,
    @unused contractFilter: MultiDomainAcsStore.ContractFilter,
    override val txLogParser: TxLogStore.Parser[TXI, TXE],
    @unused futureSupervisor: FutureSupervisor,
    @unused retryProvider: RetryProvider,
)(implicit
    @unused ec: ExecutionContext
) extends MultiDomainAcsStore
    with TxLogStore[TXI, TXE]
    with NamedLogging {

  import MultiDomainAcsStore.*

  override def lookupContractById[C, TCid <: ContractId[_], T](companion: C)(id: ContractId[_])(
      implicit companionClass: ContractCompanion[C, TCid, T]
  ): Future[Option[ContractWithState[TCid, T]]] = ???

  override def findContractWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(p: Contract[TCid, T] => Boolean)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[ContractWithState[TCid, T]]]] = ???

  override def findContractOnDomainWithOffset[C, TCid <: ContractId[_], T](
      companion: C
  )(domain: DomainId, p: Contract[TCid, T] => Boolean)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[QueryResult[Option[Contract[TCid, T]]]] = ???

  override def listContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Future[Seq[ContractWithState[TCid, T]]] = ???

  override def listReadyContracts[C, TCid <: ContractId[_], T](
      companion: C,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[ReadyContract[TCid, T]]] =
    ???

  override def listContractsOnDomain[C, TCid <: ContractId[_], T](
      companion: C,
      domain: DomainId,
      filter: Contract[TCid, T] => Boolean,
      limit: Option[Long],
  )(implicit companionClass: ContractCompanion[C, TCid, T]): Future[Seq[Contract[TCid, T]]] = ???

  override def streamReadyContracts[C, TCid <: ContractId[_], T](companion: C)(implicit
      companionClass: ContractCompanion[C, TCid, T]
  ): Source[ReadyContract[TCid, T], NotUsed] = ???

  override def streamReadyForTransferIn(): Source[TransferEvent.Out, NotUsed] = ???

  override def isReadyForTransferIn(out: TransferId): Future[Boolean] = ???

  override def signalWhenIngestedOrShutdown(domainId: DomainId, offset: String)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def signalWhenAcsCompletedOrShutdown(domainId: DomainId)(implicit
      tc: TraceContext
  ): Future[Unit] = ???

  override def ingestionSink: IngestionSink = ???

  override def getTxLogIndicesByOffset(offset: Int, limit: Int)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] = ???

  override def getLatestTxLogIndex(query: TXI => Boolean)(implicit
      ec: ExecutionContext
  ): Future[TXI] = ???

  override def getTxLogIndicesAfterEventId(
      domainId: DomainId,
      beginAfterEventId: String,
      limit: Int,
  )(implicit ec: ExecutionContext): Future[Seq[TXI]] = ???

  override def getTxLogIndicesByFilter(filter: TXI => Boolean)(implicit
      ec: ExecutionContext
  ): Future[Seq[TXI]] = ???

  override def close(): Unit = ???
}
