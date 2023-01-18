package com.daml.network.wallet.automation

import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import akka.stream.Materializer
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{OnCreateUpdateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TransferAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CoinLedgerConnection,
    globalDomain: DomainAlias,
    domainId: DomainId,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateUpdateTrigger[
      paymentCodegen.AppPaymentRequest.Contract,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
    ](
      connection,
      domainId,
      store.key.endUserParty,
      paymentCodegen.AppPaymentRequest.COMPANION,
    ) {

  // TODO(#2323) Actually initiate transfers here
  override protected def completeTask(
      contract: JavaContract[
        paymentCodegen.AppPaymentRequest.ContractId,
        paymentCodegen.AppPaymentRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    for {
      globalDomainId <- store.domains.getDomainId(globalDomain)
      outcome =
        if (globalDomainId == domainId) {
          show"Create of ${contract.contractId.contractId} on global domain, no need to transfer"
        } else {
          show"Create of ${contract.contractId.contractId} on ${domainId} requires transfer to ${globalDomainId}"
        }
    } yield TaskSuccess(outcome)
}
