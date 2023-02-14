package com.daml.network.wallet.automation

import com.digitalasset.canton.DomainAlias
import com.daml.network.automation.{
  ExpiredContractTrigger,
  ScheduledTaskTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.Contract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class ExpireAcceptedTransferOfferTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    connection: CoinLedgerConnection,
    globalDomain: DomainAlias,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ExpiredContractTrigger[
      transferOffersCodegen.AcceptedTransferOffer.Contract,
      transferOffersCodegen.AcceptedTransferOffer.ContractId,
      transferOffersCodegen.AcceptedTransferOffer,
    ](
      store.defaultAcs,
      store.listExpiredAcceptedTransferOffers,
      transferOffersCodegen.AcceptedTransferOffer.COMPANION,
    ) {

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[
        Contract[
          transferOffersCodegen.AcceptedTransferOffer.ContractId,
          transferOffersCodegen.AcceptedTransferOffer,
        ]
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      install <- store.getInstall()
      domainId <- store.domains.getDomainId(globalDomain)
      user = store.key.endUserParty.toProtoPrimitive
      _ <- user match {
        case task.work.payload.sender =>
          val cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Abort(
            task.work.contractId
          )
          connection.submitWithResultNoDedup(
            Seq(store.key.walletServiceParty),
            Seq(store.key.validatorParty, store.key.endUserParty),
            cmd,
            domainId,
          )
        case task.work.payload.receiver =>
          val cmd = install.contractId.exerciseWalletAppInstall_AcceptedTransferOffer_Withdraw(
            task.work.contractId
          )
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
    } yield TaskSuccess("expired accepted transfer offer")
  }
}
