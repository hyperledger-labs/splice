package com.daml.network.wallet.automation

import com.daml.network.automation.{
  ExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.payment as paymentCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      paymentCodegen.AppPaymentRequest.Contract,
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
    ](
      store.acs,
      store.listExpiredAppPaymentRequests,
      paymentCodegen.AppPaymentRequest.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        JavaContract[
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
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
        )
    } yield TaskSuccess("expired app payment request")
  }
}
