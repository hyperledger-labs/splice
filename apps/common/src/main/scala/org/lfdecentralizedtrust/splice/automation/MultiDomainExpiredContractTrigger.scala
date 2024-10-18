// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.store.{MultiDomainAcsStore, PageLimit}
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Contract}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import MultiDomainAcsStore.ContractState

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
    mat: Materializer,
    tracer: Tracer,
    companionClass: MultiDomainAcsStore.ContractCompanion[C, TCid, T],
) extends ScheduledTaskTrigger[AssignedContract[TCid, T]] {

  override final protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AssignedContract[TCid, T]]] =
    listExpiredContracts(now, PageLimit.tryCreate(limit))(tc)

  override final protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[AssignedContract[TCid, T]]
  )(implicit tc: TraceContext): Future[Boolean] =
    store
      .lookupContractById(companion)(task.work.contractId: TCid)
      .map(
        _.forall(_.state != ContractState.Assigned(task.work.domain))
      )
}

object MultiDomainExpiredContractTrigger {
  type Template[TCid <: ContractId[_], T] =
    MultiDomainExpiredContractTrigger[Contract.Companion.Template[TCid, T], TCid, T]
  type ListExpiredContracts[TCid <: ContractId[_], T] =
    // we use PageLimit because this is always used in the context of a trigger, where the query will be re-run repeatedly
    (CantonTimestamp, PageLimit) => TraceContext => Future[Seq[AssignedContract[TCid, T]]]
}
