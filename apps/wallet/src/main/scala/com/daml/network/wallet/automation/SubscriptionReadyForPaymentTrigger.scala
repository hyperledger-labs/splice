package com.daml.network.wallet.automation

import com.daml.network.automation.{ExpiredContractTrigger, ScheduledTaskTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class SubscriptionReadyForPaymentTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      subsCodegen.SubscriptionIdleState.Contract,
      subsCodegen.SubscriptionIdleState.ContractId,
      subsCodegen.SubscriptionIdleState,
    ](
      store.acs,
      store.listSubscriptionStatesReadyForPayment,
      subsCodegen.SubscriptionIdleState.COMPANION,
    ) {

  override protected def processTask(
      task: ScheduledTaskTrigger.ReadyTask[
        JavaContract[
          subsCodegen.SubscriptionIdleState.ContractId,
          subsCodegen.SubscriptionIdleState,
        ]
      ]
  )(implicit tc: TraceContext): Future[Option[String]] = {
    import com.daml.network.util.PrettyInstances.*

    val stateCid = task.work.contractId
    val operation = new installCodegen.coinoperation.CO_SubscriptionMakePayment(stateCid)
    treasury
      .enqueueCoinOperation(operation)
      .map {
        case failedOperation: installCodegen.coinoperationoutcome.COO_Error =>
          val error = show"Daml exception\n${failedOperation.toValue}"
          logger.warn(s"Failed making a subscription payment on state $stateCid: $error")
          Some(s"skipped making a subscription payment, as it failed with: $error")

        // TODO(#1968): make this the specific case, and add a default case to be robust under changes
        case _ => Some("made subscription payment")
      }
  }
}
