package com.daml.network.automation

import com.daml.network.store.{CNNodeAppStore, MultiDomainAcsStore}
import MultiDomainAcsStore.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import akka.stream.Materializer
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.daml.ledger.javaapi.data.Template as CodegenTemplate
import com.daml.ledger.javaapi.data.codegen.{ContractTypeCompanion, DamlRecord, ContractId}
import com.daml.network.environment.ledger.api.LedgerClient

import scala.concurrent.{ExecutionContext, Future}

class TransferOutTrigger[C <: ContractTypeCompanion[_, TCid, _, T], TCid <: ContractId[_], T](
    override protected val context: TriggerContext,
    store: CNNodeAppStore[_, _],
    connection: CNLedgerConnection,
    targetDomain: DomainAlias,
    partyId: PartyId,
    companion: C,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    companionClass: MultiDomainAcsStore.ContractCompanion[C, TCid, T],
) extends OnReadyContractTrigger[C, TCid, T](
      store,
      companion,
    ) {

  override protected def completeTask(
      task: ReadyContract[
        TCid,
        T,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val contract = task.contract
    for {
      targetDomainId <- store.domains.getDomainId(targetDomain)
      cid = PrettyContractId(companion.TEMPLATE_ID, contract.contractId)
      outcome <-
        if (task.domain == targetDomainId) {
          Future.successful(
            show"Create of $cid already on target domain ${targetDomainId}, no need to transfer"
          )
        } else
          for {
            _ <- connection.submitTransferAndAwaitIngestionNoDedup(
              store.multiDomainAcsStore,
              submitter = partyId,
              command = LedgerClient.TransferCommand.Out(
                contractId = contract.contractId,
                source = task.domain,
                target = targetDomainId,
              ),
            )
          } yield show"Submitted transfer out of $cid from ${task.domain} to ${targetDomainId}"

    } yield TaskSuccess(outcome)
  }
}

object TransferOutTrigger {
  type Template[TCid <: ContractId[T], T <: CodegenTemplate] =
    TransferOutTrigger[Contract.Companion.Template[TCid, T], TCid, T]
  type Interface[I, Id <: ContractId[I], View <: DamlRecord[View]] =
    TransferOutTrigger[Contract.Companion.Interface[Id, I, View], Id, View]
}
