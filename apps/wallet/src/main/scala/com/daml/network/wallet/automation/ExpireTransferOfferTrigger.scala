package com.daml.network.wallet.automation

import com.daml.network.automation.{ExpiredContractTrigger, ScheduledTaskTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.transferoffer as transferOffersCodegen
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.JavaContract
import com.daml.network.wallet.store.UserWalletStore
import com.digitalasset.canton.tracing.TraceContext
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

  override protected def processTask(
      task: ScheduledTaskTrigger.ReadyTask[
        JavaContract[
          transferOffersCodegen.TransferOffer.ContractId,
          transferOffersCodegen.TransferOffer,
        ]
      ]
  )(implicit tc: TraceContext): Future[Option[String]] = {
    for {
      install <- store.getInstall()
      cmd = install.contractId.exerciseWalletAppInstall_TransferOffer_Expire(
        task.work.contractId,
        store.key.endUserParty.toProtoPrimitive,
      )
      _ <- connection
        .submitWithResultNoDedup(
          Seq(store.key.walletServiceParty),
          Seq(store.key.validatorParty, store.key.endUserParty),
          cmd,
        )
    } yield Some("expired transfer offer")
  }
}
