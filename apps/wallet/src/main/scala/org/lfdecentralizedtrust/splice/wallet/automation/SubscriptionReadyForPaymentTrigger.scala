// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  install as installCodegen,
  subscriptions as subsCodegen,
}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class SubscriptionReadyForPaymentTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      subsCodegen.SubscriptionIdleState.ContractId,
      subsCodegen.SubscriptionIdleState,
    ](
      store.multiDomainAcsStore,
      store.listSubscriptionStatesReadyForPayment,
      subsCodegen.SubscriptionIdleState.COMPANION,
    ) {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          subsCodegen.SubscriptionIdleState.ContractId,
          subsCodegen.SubscriptionIdleState,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

    val stateCid = task.work.contractId
    val operation = new installCodegen.amuletoperation.CO_SubscriptionMakePayment(stateCid)
    treasury
      .enqueueAmuletOperation(operation)
      .map {
        case _: installCodegen.amuletoperationoutcome.COO_SubscriptionPayment =>
          TaskSuccess("made subscription payment")
        case failedOperation: installCodegen.amuletoperationoutcome.COO_Error =>
          val msg =
            show"Failed making subscription payment due to Daml exception\n${failedOperation.toValue}"
          // We're throwing this as INTERNAL to avoid that the polling trigger retries this task in a tight loop.
          // TODO(DACH-NY/canton-network-node#2034): INTERNAL is not the right option for a ITR_InsufficientFunds error. There we should actually try to create a marker on-ledger to reach out to the user for a decision on whether to continue trying to pay this subscription or not.
          throw Status.INTERNAL.withDescription(msg).asRuntimeException()

        case unknown =>
          throw Status.INTERNAL
            .withDescription(s"Unexpected amulet operation outcome: $unknown")
            .asRuntimeException()
      }
  }
}
