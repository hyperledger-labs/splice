package com.daml.network.wallet.automation

import com.digitalasset.canton.DomainAlias
import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.util.Contract
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class WalletAppInstallTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
    globalDomain: DomainAlias,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger.Template[
      installCodegen.WalletAppInstall.ContractId,
      installCodegen.WalletAppInstall,
    ](
      walletManager.store,
      () => walletManager.store.domains.signalWhenConnected(globalDomain),
      installCodegen.WalletAppInstall.COMPANION,
    ) {

  // TODO(#763): not handling archive events, uninstalling wallets without a restart is not supported yet
  override def completeTask(
      install: Contract[
        installCodegen.WalletAppInstall.ContractId,
        installCodegen.WalletAppInstall,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    Future {
      val endUserName = install.payload.endUserName
      if (walletManager.getOrCreateUserWallet(install))
        TaskSuccess(s"onboarded wallet end-user '$endUserName'")
      else {
        logger.warn(s"Unexpected duplicate on-boarding of wallet user '$endUserName'")
        TaskSuccess(s"skipped duplicate on-boarding wallet end-user '$endUserName'")
      }
    }

}
