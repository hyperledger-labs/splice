package com.daml.network.automation

import com.daml.network.store.CoinAppStore
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import akka.stream.Materializer
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.{CoinLedgerConnection, LedgerClient}
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.daml.ledger.javaapi.data.{Template as CodegenTemplate}
import com.daml.ledger.javaapi.data.codegen.{
  Contract as CodegenContract,
  ContractTypeCompanion,
  DamlRecord,
  ContractCompanion,
  ContractId,
  InterfaceCompanion,
}

import scala.concurrent.{ExecutionContext, Future}

class TransferOutTrigger[C <: ContractTypeCompanion[_, TCid, _, T], TCid <: ContractId[_], T](
    override protected val context: TriggerContext,
    store: CoinAppStore[_, _],
    connection: CoinLedgerConnection,
    targetDomain: DomainAlias,
    sourceDomainId: DomainId,
    partyId: PartyId,
    companion: C,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    companionClass: OnCreateTrigger.Companion[C, TCid, T],
) extends OnCreateTrigger[C, TCid, T](
      store,
      () => Future.successful(sourceDomainId),
      companion,
    ) {

  override protected lazy val loggerFactory: NamedLoggerFactory =
    super.loggerFactory.append("domainId", sourceDomainId.toProtoPrimitive)

  override protected def completeTask(
      contract: Contract[
        TCid,
        T,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      targetDomainId <- store.domains.getDomainId(targetDomain)
      cid = PrettyContractId(companion.TEMPLATE_ID, contract.contractId)
      outcome <-
        if (targetDomainId == sourceDomainId) {
          Future.successful(
            show"Create of $cid already on target domain ${targetDomainId}, no need to transfer"
          )
        } else
          for {
            _ <- connection.submitTransferNoDedup(
              submitter = partyId,
              command = LedgerClient.TransferCommand.Out(
                contractId = contract.contractId,
                source = sourceDomainId,
                target = targetDomainId,
              ),
            )
          } yield show"Submitting transfer out of $cid from ${sourceDomainId} to ${targetDomainId}"

    } yield TaskSuccess(outcome)
}

object TransferOutTrigger {
  type Template[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: CodegenTemplate] =
    TransferOutTrigger[ContractCompanion[TC, TCid, T], TCid, T]
  type Interface[I, Id <: ContractId[I], View <: DamlRecord[View]] =
    TransferOutTrigger[InterfaceCompanion[I, Id, View], Id, View]
}
