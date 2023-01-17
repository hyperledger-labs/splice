package com.daml.network.wallet.automation

import com.daml.network.automation.{
  ExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireTransferOfferTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      transferOffersCodegen.TransferOffer.Contract,
      transferOffersCodegen.TransferOffer.ContractId,
      transferOffersCodegen.TransferOffer,
    ](
      store.acs,
      store.listExpiredTransferOffers,
      transferOffersCodegen.TransferOffer.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        JavaContract[
          transferOffersCodegen.TransferOffer.ContractId,
          transferOffersCodegen.TransferOffer,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      domainId <- store.domains.getUniqueDomainId()
      user = store.key.endUserParty.toProtoPrimitive
      _ <- user match {
        case task.work.payload.sender =>
          val cmd = install.contractId.exerciseWalletAppInstall_TransferOffer_Withdraw(
            task.work.contractId
          )
          logger.debug("Withdrawing expired transfer offer as sender")
          connection.submitWithResultNoDedup(
            Seq(store.key.walletServiceParty),
            Seq(store.key.validatorParty, store.key.endUserParty),
            cmd,
            domainId,
          )
        case task.work.payload.receiver =>
          val cmd = install.contractId.exerciseWalletAppInstall_TransferOffer_Reject(
            task.work.contractId
          )
          logger.debug("Rejecting expired transfer offer as receiver")
          connection.submitWithResultNoDedup(
            Seq(store.key.walletServiceParty),
            Seq(store.key.validatorParty, store.key.endUserParty),
            cmd,
            domainId,
          )
        case _ =>
          Future.failed(
            new StatusRuntimeException(
              Status.INTERNAL.withDescription(
                s"User ($user) is unexpectedly neither sender ($task.work.payload.sender) nor receiver ($task.work.payload.receiver)"
              )
            )
          )
      }
    } yield TaskSuccess("expired transfer offer")
  }
}
