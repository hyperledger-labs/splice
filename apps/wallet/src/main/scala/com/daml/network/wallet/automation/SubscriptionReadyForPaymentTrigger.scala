package com.daml.network.wallet.automation

import com.daml.network.automation.{
  ExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.{
  install as installCodegen,
  subscriptions as subsCodegen,
}
import com.daml.network.util.Contract
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
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
      subsCodegen.SubscriptionIdleState.ContractId,
      subsCodegen.SubscriptionIdleState,
    ](
      store.defaultAcs,
      store.listSubscriptionStatesReadyForPayment,
      subsCodegen.SubscriptionIdleState.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        Contract[
          subsCodegen.SubscriptionIdleState.ContractId,
          subsCodegen.SubscriptionIdleState,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    import com.daml.network.util.PrettyInstances.*

    val stateCid = task.work.contractId
    val operation = new installCodegen.coinoperation.CO_SubscriptionMakePayment(stateCid)
    treasury
      .enqueueCoinOperation(operation)
      .map {
        case _: installCodegen.coinoperationoutcome.COO_SubscriptionPayment =>
          TaskSuccess("made subscription payment")
        case failedOperation: installCodegen.coinoperationoutcome.COO_Error =>
          val msg =
            show"Failed making subscription payment due to Daml exception\n${failedOperation.toValue}"
          // We're throwing this as INTERNAL to avoid that the polling trigger retries this task in a tight loop.
          // TODO(#2034): INTERNAL is not the right option for a ITR_InsufficientFunds error. There we should actually try to create a marker on-ledger to reach out to the user for a decision on whether to continue trying to pay this subscription or not.
          throw Status.INTERNAL.withDescription(msg).asRuntimeException()

        case unknown =>
          throw Status.INTERNAL
            .withDescription(s"Unexpected coin operation outcome: $unknown")
            .asRuntimeException()
      }
  }
}
