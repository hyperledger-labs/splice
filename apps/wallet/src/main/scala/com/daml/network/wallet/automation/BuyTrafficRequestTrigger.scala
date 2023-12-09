package com.daml.network.wallet.automation

import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cc.coinrules.invalidtransferreason
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_CompleteBuyTrafficRequest
import com.daml.network.codegen.java.cn.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  install as installCodegen,
}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

class BuyTrafficRequestTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
    connection: CNLedgerConnection,
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

  override def completeTask(
      trafficRequest: AssignedContract[
        trafficRequestCodegen.BuyTrafficRequest.ContractId,
        trafficRequestCodegen.BuyTrafficRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val operation = new CO_CompleteBuyTrafficRequest(trafficRequest.contractId)
    treasury
      .enqueueCoinOperation(operation)
      .flatMap {
        case failedOperation: installCodegen.coinoperationoutcome.COO_Error =>
          failedOperation.invalidTransferReasonValue match {
            case fundsError: invalidtransferreason.ITR_InsufficientFunds =>
              val missingStr = s"(missing ${fundsError.missingAmount} CC)"
              logger.info(
                s"Insufficient funds to purchase traffic $missingStr, cancelling traffic request"
              )
              cancelTrafficRequest(trafficRequest, s"out of funds $missingStr")
            case otherError: invalidtransferreason.ITR_Other =>
              // TODO(#8903): Create COO_Error subtypes for these cases
              val unknownDomainId: Regex = "The requirement 'Known domain-id' was not met.".r
              val insufficientTrafficAmount: Regex =
                "The requirement 'trafficAmount (.*) is at least as much as the configured minTopupAmount (.*)' was not met.".r
              otherError.description match {
                case unknownDomainId() =>
                  cancelTrafficRequest(
                    trafficRequest,
                    s"unknown domainId ${trafficRequest.payload.domainId}",
                  )
                case insufficientTrafficAmount(requestedAmount, minimumAmount) =>
                  cancelTrafficRequest(
                    trafficRequest,
                    s"not enough traffic requested (trafficAmount $requestedAmount < minTopupAmount $minimumAmount)",
                  )
                case _ =>
                  val msg = s"Unexpectedly failed to accepted transfer due to $otherError"
                  // We report this as INTERNAL, as we don't want to retry on this.
                  Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
              }
            case otherError =>
              val msg = s"Unexpectedly failed to complete buy traffic request due to $otherError"
              // We report this as INTERNAL, as we don't want to retry on this.
              Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
          }

        case _: installCodegen.coinoperationoutcome.COO_CompleteBuyTrafficRequest =>
          Future.successful(TaskSuccess("Completed buy traffic request"))

        case unknownOutcome =>
          val msg = s"Unexpected coin-operation outcome $unknownOutcome"
          Future.failed(Status.INTERNAL.withDescription(msg).asRuntimeException())
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
