// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet.automation

import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.invalidtransferreason
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.install.amuletoperation.CO_CompleteBuyTrafficRequest
import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  install as installCodegen,
}
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.wallet.store.UserWalletStore
import org.lfdecentralizedtrust.splice.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

class CompleteBuyTrafficRequestTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
    connection: SpliceLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      trafficRequestCodegen.BuyTrafficRequest.ContractId,
      trafficRequestCodegen.BuyTrafficRequest,
    ](
      store,
      trafficRequestCodegen.BuyTrafficRequest.COMPANION,
    ) {

  override protected def extraMetricLabels = Seq(
    "party" -> store.key.endUserParty.toString
  )

  override def completeTask(
      trafficRequest: AssignedContract[
        trafficRequestCodegen.BuyTrafficRequest.ContractId,
        trafficRequestCodegen.BuyTrafficRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    if (trafficRequest.contract.payload.expiresAt.isBefore(context.clock.now.toInstant)) {
      Future.successful(TaskSuccess("Traffic request is expired. Skipping."))
    } else {
      val operation = new CO_CompleteBuyTrafficRequest(trafficRequest.contractId)
      treasury
        .enqueueAmuletOperation(operation)
        .flatMap {
          case failedOperation: installCodegen.amuletoperationoutcome.COO_Error =>
            failedOperation.invalidTransferReasonValue match {
              case fundsError: invalidtransferreason.ITR_InsufficientFunds =>
                val missingStr = s"(missing ${fundsError.missingAmount} CC)"
                logger.info(
                  s"Insufficient funds to purchase traffic $missingStr, cancelling traffic request"
                )
                cancelTrafficRequest(trafficRequest, s"out of funds $missingStr")
              case domainError: invalidtransferreason.ITR_UnknownSynchronizer =>
                cancelTrafficRequest(
                  trafficRequest,
                  s"unknown synchronizerId ${domainError.synchronizerId}",
                )
              case topupAmountError: invalidtransferreason.ITR_InsufficientTopupAmount =>
                cancelTrafficRequest(
                  trafficRequest,
                  s"not enough traffic requested (trafficAmount ${topupAmountError.requestedTopupAmount} < minTopupAmount ${topupAmountError.minTopupAmount})",
                )
              case otherError =>
                val msg = s"Unexpectedly failed to buy extra traffic due to $otherError"
                // We report this as INTERNAL, as we don't want to retry on this.
                Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
            }

          case _: installCodegen.amuletoperationoutcome.COO_CompleteBuyTrafficRequest =>
            Future.successful(TaskSuccess("Completed buy traffic request"))

          case unknownOutcome =>
            val msg = s"Unexpected amulet-operation outcome $unknownOutcome"
            Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
        }
    }
  }

  private def cancelTrafficRequest(
      trafficRequest: AssignedContract[
        trafficRequestCodegen.BuyTrafficRequest.ContractId,
        trafficRequestCodegen.BuyTrafficRequest,
      ],
      reason: String,
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      cmd = install.exercise(
        _.exerciseWalletAppInstall_BuyTrafficRequest_Cancel(
          trafficRequest.contractId,
          reason,
        )
      )
      _ <- connection
        .submit(Seq(store.key.validatorParty), Seq(store.key.endUserParty), cmd)
        .noDedup
        .yieldResult()
    } yield TaskSuccess(s"Cancelled buy traffic request with $reason")
  }

}
