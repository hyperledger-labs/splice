package com.daml.network.wallet.automation

import org.apache.pekko.stream.Materializer
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.wallet.install as installCodegen
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.util.AssignedContract
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class WalletAppInstallTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[
      installCodegen.WalletAppInstall.ContractId,
      installCodegen.WalletAppInstall,
    ](
      walletManager.store,
      installCodegen.WalletAppInstall.COMPANION,
    ) {

  override def completeTask(
      install: AssignedContract[
        installCodegen.WalletAppInstall.ContractId,
        installCodegen.WalletAppInstall,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val endUserName = install.payload.endUserName
    val endUserParty = PartyId.tryFromProtoPrimitive(install.payload.endUserParty)
    for {
      // User rights might have gone lost during a hard migration or a disaster recovery.
      _ <- connection.grantUserRights(
        walletManager.validatorUser,
        Seq(endUserParty),
        Seq(),
      )
    } yield walletManager.getOrCreateUserWallet(install.contract) match {
      case UnlessShutdown.AbortedDueToShutdown =>
        TaskSuccess(
          s"skipped or aborted onboarding wallet end-user '$endUserName', as we are shutting down."
        )
      case UnlessShutdown.Outcome(true) =>
        TaskSuccess(s"onboarded wallet end-user '$endUserName'")
      case UnlessShutdown.Outcome(false) =>
        logger.info(s"Unexpected duplicate on-boarding of wallet end-user '$endUserName'")
        TaskSuccess(s"skipped duplicate on-boarding wallet end-user '$endUserName'")
    }
  }
}
