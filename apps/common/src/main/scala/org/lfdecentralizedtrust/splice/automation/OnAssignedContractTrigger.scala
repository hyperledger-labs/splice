// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.Source
import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.store.{AppStore, MultiDomainAcsStore}
import org.lfdecentralizedtrust.splice.util.{Contract, AssignedContract}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import MultiDomainAcsStore.ContractState

/** A trigger for processing ready contracts. Note that the trigger
  * can get called multiple times for the same contract as it gets transferred betweend domains.
  */
abstract class OnAssignedContractTrigger[C, TCid <: ContractId[?], T](
    store: AppStore,
    companion: C,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    companionClass: MultiDomainAcsStore.ContractCompanion[C, TCid, T],
) extends SourceBasedTrigger[AssignedContract[TCid, T]] {

  override protected def source(implicit
      traceContext: TraceContext
  ): Source[AssignedContract[TCid, T], NotUsed] =
    store.multiDomainAcsStore.streamAssignedContracts(companion)

  override final def isStaleTask(
      task: AssignedContract[TCid, T]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractById(companion)(task.contractId: TCid)
      .map(
        _.forall(_.state != ContractState.Assigned(task.domain))
      )
}

object OnAssignedContractTrigger {
  type Template[TCid <: ContractId[?], T] =
    OnAssignedContractTrigger[Contract.Companion.Template[TCid, T], TCid, T]
}
