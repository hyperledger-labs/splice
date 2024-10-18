// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.automation

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.payment as walletCodegen
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.splitwell.store.SplitwellStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class TerminatedAppPaymentTrigger(
    override protected val context: TriggerContext,
    store: SplitwellStore,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      walletCodegen.TerminatedAppPayment.ContractId,
      walletCodegen.TerminatedAppPayment,
    ](store, walletCodegen.TerminatedAppPayment.COMPANION) {

  override def completeTask(
      task: AssignedContract[
        walletCodegen.TerminatedAppPayment.ContractId,
        walletCodegen.TerminatedAppPayment,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      transferInProgressO <- store.lookupTransferInProgress(task.contract.payload.reference)
      _ <- transferInProgressO.value match {
        case None =>
          throw Status.INTERNAL
            .withDescription("No corresponding TransferInProgress for TerminatedAppPayment")
            .asRuntimeException()
        case Some(transferInProgress) =>
          connection
            .submit(
              Seq(store.key.providerParty),
              Seq.empty,
              task.exercise { tapContractId =>
                transferInProgress.contractId.exerciseTransferInProgress_Terminate(
                  store.key.providerParty.toProtoPrimitive,
                  tapContractId,
                )
              },
            )
            .noDedup
            .yieldUnit()
      }
    } yield TaskSuccess("Archived TransferInProgress because corresponding payment got terminated")
  }
}
