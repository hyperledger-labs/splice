package com.daml.network.wallet.automation

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import akka.stream.Materializer
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{OnCreateUpdateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.{CoinLedgerConnection, LedgerClient}
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{Contract, ContractCompanion, ContractId}

import scala.concurrent.{ExecutionContext, Future}

class TransferOutTrigger[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CoinLedgerConnection,
    targetDomain: DomainAlias,
    domainId: DomainId,
    templateCompanion: ContractCompanion[TC, TCid, T],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateUpdateTrigger[TC, TCid, T](
      connection,
      domainId,
      store.key.endUserParty,
      templateCompanion,
    ) {

  override protected lazy val loggerFactory: NamedLoggerFactory =
    super.loggerFactory.append("domainId", domainId.toProtoPrimitive)

  override protected def completeTask(
      contract: JavaContract[
        TCid,
        T,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      targetDomainId <- store.domains.getDomainId(targetDomain)
      cid = PrettyContractId(templateCompanion.TEMPLATE_ID, contract.contractId)
      outcome <-
        if (targetDomainId == domainId) {
          Future.successful(
            show"Create of $cid already on target domain ${targetDomainId}, no need to transfer"
          )
        } else
          for {
            _ <- connection.submitTransferNoDedup(
              submitter = store.key.endUserParty,
              command = LedgerClient.TransferCommand.Out(
                contractId = contract.contractId,
                source = domainId,
                target = targetDomainId,
              ),
            )
          } yield show"Submitting transfer out of $cid from ${domainId} to ${targetDomainId}"

    } yield TaskSuccess(outcome)
}
