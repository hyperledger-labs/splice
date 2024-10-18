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
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as paymentCodegen
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class ExpireAppPaymentRequestsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      paymentCodegen.AppPaymentRequest.ContractId,
      paymentCodegen.AppPaymentRequest,
    ](
      store.multiDomainAcsStore,
      store.listExpiredAppPaymentRequests,
      paymentCodegen.AppPaymentRequest.COMPANION,
    ) {

  override protected def extraMetricLabels = Seq("party" -> store.key.endUserParty.toString)

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          paymentCodegen.AppPaymentRequest.ContractId,
          paymentCodegen.AppPaymentRequest,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      cmd = install.exercise(
        _.exerciseWalletAppInstall_AppPaymentRequest_Expire(
          task.work.contractId
        )
      )
      _ <- connection
        .submit(Seq(store.key.validatorParty), Seq(store.key.endUserParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess("expired app payment request")
  }
}
