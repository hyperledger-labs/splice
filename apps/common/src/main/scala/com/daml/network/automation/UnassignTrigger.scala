// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.automation

import org.apache.pekko.stream.Materializer
import com.daml.ledger.javaapi.data.Template as CodegenTemplate
import com.daml.ledger.javaapi.data.codegen.{ContractId, ContractTypeCompanion, DamlRecord}
import com.daml.network.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.environment.ledger.api.LedgerClient
import com.daml.network.store.{AppStore, MultiDomainAcsStore}
import com.daml.network.util.{Contract, AssignedContract}
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

import UnassignTrigger.GetTargetDomain

class UnassignTrigger[C <: ContractTypeCompanion[_, TCid, _, T], TCid <: ContractId[_], T](
    override protected val context: TriggerContext,
    store: AppStore,
    connection: SpliceLedgerConnection,
    targetDomain: GetTargetDomain,
    partyId: PartyId,
    companion: C,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    companionClass: MultiDomainAcsStore.ContractCompanion[C, TCid, T],
) extends OnAssignedContractTrigger[C, TCid, T](
      store,
      companion,
    ) {

  override protected def extraMetricLabels = Seq("party" -> partyId.toString)

  override protected def completeTask(
      task: AssignedContract[
        TCid,
        T,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val contract = task.contract
    for {
      targetDomainId <- targetDomain()(tc)
      cid = PrettyContractId(companion.TEMPLATE_ID, contract.contractId)
      outcome <-
        if (task.domain == targetDomainId) {
          Future.successful(
            show"Create of $cid already on target domain ${targetDomainId}, no need to transfer"
          )
        } else
          for {
            _ <- connection.submitReassignmentAndWaitNoDedup(
              submitter = partyId,
              command = LedgerClient.ReassignmentCommand.Unassign(
                contractId = contract.contractId,
                source = task.domain,
                target = targetDomainId,
              ),
            )
          } yield show"Submitted unassign of $cid from ${task.domain} to ${targetDomainId}"

    } yield TaskSuccess(outcome)
  }

  private[automation] override final def additionalRetryableConditions = {
    import io.grpc.Status
    import com.daml.error.ErrorCategory.InvalidIndependentOfSystemState
    import com.daml.network.environment.RetryProvider.Condition
    /*
    targeting this error, for which we want to retry (see #8267):
    category=Some(InvalidIndependentOfSystemState)
    statusCode=INVALID_ARGUMENT
    description=INVALID_ARGUMENT(8,5aebaf00): The submitted command has invalid arguments: Cannot transfer-out contract `ContractId(007ccd89ecfe3d1a64bac02fb8d5c5c06e4226c2ff2cdb949c1865b9e85b75ab0dca02122035f2ca0ec64001b3712163b7804b93e9eaff2aa6cf2b472e2cb9b5a60aab66cf)` because it's not active. Current status TransferredAway(global-domain::1220bcde7452...)
    ErrorInfoDetail(INVALID_ARGUMENT,Map(participant -> aliceParticipant, tid -> 5aebaf00957d76d13cb624aa76aec24e, category -> 8, definite_answer -> false))
    RequestInfoDetail(5aebaf00957d76d13cb624aa76aec24e) io.grpc.StatusRuntimeException: INVALID_ARGUMENT: INVALID_ARGUMENT(8,5aebaf00): The submitted command has invalid arguments: Cannot transfer-out contract `ContractId(007ccd89ecfe3d1a64bac02fb8d5c5c06e4226c2ff2cdb949c1865b9e85b75ab0dca02122035f2ca0ec64001b3712163b7804b93e9eaff2aa6cf2b472e2cb9b5a60aab66cf)` because it's not active. Current status TransferredAway(global-domain::1220bcde7452...)
     */
    Map(Status.Code.INVALID_ARGUMENT -> Condition.Category(InvalidIndependentOfSystemState))
  }
}

object UnassignTrigger {
  type Template[TCid <: ContractId[T], T <: CodegenTemplate] =
    UnassignTrigger[Contract.Companion.Template[TCid, T], TCid, T]
  type Interface[I, Id <: ContractId[I], View <: DamlRecord[View]] =
    UnassignTrigger[Contract.Companion.Interface[Id, I, View], Id, View]

  type GetTargetDomain = () => TraceContext => Future[DomainId]
}
