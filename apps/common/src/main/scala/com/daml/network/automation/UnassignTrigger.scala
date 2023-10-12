package com.daml.network.automation

import akka.stream.Materializer
import com.daml.ledger.javaapi.data.Template as CodegenTemplate
import com.daml.ledger.javaapi.data.codegen.{ContractId, ContractTypeCompanion, DamlRecord}
import com.daml.network.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.LedgerClient
import com.daml.network.store.{CNNodeAppStore, MultiDomainAcsStore}
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
    store: CNNodeAppStore[_, _],
    connection: CNLedgerConnection,
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
}

object UnassignTrigger {
  type Template[TCid <: ContractId[T], T <: CodegenTemplate] =
    UnassignTrigger[Contract.Companion.Template[TCid, T], TCid, T]
  type Interface[I, Id <: ContractId[I], View <: DamlRecord[View]] =
    UnassignTrigger[Contract.Companion.Interface[Id, I, View], Id, View]

  type GetTargetDomain = () => TraceContext => Future[DomainId]
}
