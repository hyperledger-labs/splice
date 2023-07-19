package com.daml.network.wallet.automation

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.util.ReadyContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
    ](
      store.multiDomainAcsStore,
      store.listExpiredAppPaymentRequests,
      paymentCodegen.AppPaymentRequest.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        ReadyContract[
          paymentCodegen.AppPaymentRequest.ContractId,
          paymentCodegen.AppPaymentRequest,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      cmd = install.contractId.exerciseWalletAppInstall_AppPaymentRequest_Expire(
        task.work.contractId
      )
      _ <- connection
        .submitWithResultNoDedup(
          Seq(store.key.validatorParty),
          Seq(store.key.endUserParty),
          cmd,
          task.work.domain,
        )
    } yield TaskSuccess("expired app payment request")
  }
}
