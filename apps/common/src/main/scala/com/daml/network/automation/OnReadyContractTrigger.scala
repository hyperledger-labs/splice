package com.daml.network.automation

import akka.stream.scaladsl.Source
import akka.stream.Materializer
import akka.NotUsed
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.store.{CNNodeAppStore, MultiDomainAcsStore}
import MultiDomainAcsStore.{ContractState, ReadyContract}
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A trigger for processing ready contracts. Note that the trigger
  * can get called multiple times for the same contract as it gets transferred betweend domains.
  */
abstract class OnReadyContractTrigger[C, TCid <: ContractId[_], T](
    store: CNNodeAppStore[_, _],
    companion: C,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    companionClass: MultiDomainAcsStore.ContractCompanion[C, TCid, T],
) extends SourceBasedTrigger[ReadyContract[TCid, T]] {

  override protected val source: Source[ReadyContract[TCid, T], NotUsed] =
    store.multiDomainAcsStore.streamReadyContracts(companion)

  override final def isStaleTask(
      task: ReadyContract[TCid, T]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractById(companion)(task.contract.contractId: TCid)
      .map(
        _.forall(_.state != ContractState.Assigned(task.domain))
      )
}

object OnReadyContractTrigger {
  type Template[TCid <: ContractId[_], T] =
    OnReadyContractTrigger[Contract.Companion.Template[TCid, T], TCid, T]
}
