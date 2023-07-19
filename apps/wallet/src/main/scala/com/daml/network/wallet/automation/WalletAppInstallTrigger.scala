package com.daml.network.wallet.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  OnReadyContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.wallet.install as installCodegen
import com.daml.network.util.ReadyContract
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class WalletAppInstallTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnReadyContractTrigger.Template[
      installCodegen.WalletAppInstall.ContractId,
      installCodegen.WalletAppInstall,
    ](
      walletManager.store,
      installCodegen.WalletAppInstall.COMPANION,
    ) {

  override def completeTask(
      install: ReadyContract[
        installCodegen.WalletAppInstall.ContractId,
        installCodegen.WalletAppInstall,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] =
    Future {
      val endUserName = install.payload.endUserName
      walletManager.getOrCreateUserWallet(install.contract) match {
        case UnlessShutdown.AbortedDueToShutdown =>
          TaskSuccess(
            s"skipped or aborted onboarding wallet end-user '$endUserName', as we are shutting down."
          )
        case UnlessShutdown.Outcome(true) =>
          TaskSuccess(s"onboarded wallet end-user '$endUserName'")
        case UnlessShutdown.Outcome(false) =>
          logger.warn(s"Unexpected duplicate on-boarding of wallet end-user '$endUserName'")
          TaskSuccess(s"skipped duplicate on-boarding wallet end-user '$endUserName'")
      }
    }
}
