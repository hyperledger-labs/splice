package com.daml.network.automation

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.daml.network.store.MultiDomainAcsStore
import MultiDomainAcsStore.{ContractState, ReadyContract}
import com.daml.network.util.Contract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** A trigger for processing expired contracts whose expiry archives exactly them.
  *
  * Use [[ScheduledTaskTrigger]] for more complex expiry choices.
  */
// TODO(tech-debt): if we happen to find LOTS of instances that just expire the contract based on its expiry date, then we should consider introducing a Daml-level interface 'ExpiringContract' and handle all of them using single trigger.
abstract class MultiDomainExpiredContractTrigger[
    C,
    TCid <: ContractId[_],
    T,
](
    store: MultiDomainAcsStore,
    listExpiredContracts: MultiDomainExpiredContractTrigger.ListExpiredContracts[TCid, T],
    companion: C,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    companionClass: MultiDomainAcsStore.ContractCompanion[C, TCid, T],
) extends ScheduledTaskTrigger[ReadyContract[TCid, T]] {

  override final protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[ReadyContract[TCid, T]]] =
    listExpiredContracts(now, limit)

  override final protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[ReadyContract[TCid, T]]
  )(implicit tc: TraceContext): Future[Boolean] =
    store
      .lookupContractById(companion)(task.work.contract.contractId: TCid)
      .map(
        _.forall(_.state != ContractState.Assigned(task.work.domain))
      )
}

object MultiDomainExpiredContractTrigger {
  type Template[TCid <: ContractId[_], T] =
    MultiDomainExpiredContractTrigger[Contract.Companion.Template[TCid, T], TCid, T]
  type ListExpiredContracts[TCid <: ContractId[_], T] =
    (CantonTimestamp, Int) => Future[Seq[ReadyContract[TCid, T]]]
}
