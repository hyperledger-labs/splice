package com.daml.network.wallet.automation

import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{
  ExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.util.Contract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CNLedgerConnection,
    globalDomain: DomainAlias,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
    ](
      store.defaultAcs,
      store.listExpiredAppPaymentRequests,
      paymentCodegen.AppPaymentRequest.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        Contract[
          paymentCodegen.AppPaymentRequest.ContractId,
          paymentCodegen.AppPaymentRequest,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      domainId <- store.domains.getDomainId(globalDomain)
      install <- store.getInstall()
      cmd = install.contractId.exerciseWalletAppInstall_AppPaymentRequest_Expire(
        task.work.contractId
      )
      _ <- connection
        .submitWithResultNoDedup(
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
          domainId,
        )
    } yield TaskSuccess("expired app payment request")
  }
}
