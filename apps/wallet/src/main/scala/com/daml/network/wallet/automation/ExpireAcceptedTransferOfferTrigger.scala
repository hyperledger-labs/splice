package com.daml.network.wallet.automation

import com.daml.network.automation.{
  MultiDomainExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireAcceptedTransferOfferTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends MultiDomainExpiredContractTrigger.Template[
      transferOffersCodegen.AcceptedTransferOffer.ContractId,
      transferOffersCodegen.AcceptedTransferOffer,
    ](
      store.multiDomainAcsStore,
      store.listExpiredAcceptedTransferOffers,
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        AssignedContract[
          transferOffersCodegen.AcceptedTransferOffer.ContractId,
          transferOffersCodegen.AcceptedTransferOffer,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      user = store.key.endUserParty.toProtoPrimitive
      _ <- user match {
        case task.work.contract.payload.sender =>
          val cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Abort(
            task.work.contractId
          )
          connection.submitWithResultNoDedup(
            Seq(store.key.validatorParty),
            Seq(store.key.endUserParty),
            cmd,
            task.work.domain,
          )
        case task.work.contract.payload.receiver =>
          val cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Withdraw(
            task.work.contractId
          )
          connection.submitWithResultNoDedup(
            Seq(store.key.validatorParty),
            Seq(store.key.endUserParty),
            cmd,
            task.work.domain,
          )
        case _ =>
          Future.failed(
            new StatusRuntimeException(
              Status.INTERNAL.withDescription(
                s"User ($user) is unexpectedly neither sender ($task.work.contract.payload.sender) nor receiver ($task.work.contract.payload.receiver)"
              )
            )
          )
      }
    } yield TaskSuccess("expired accepted transfer offer")
  }
}
