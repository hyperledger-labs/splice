package com.daml.network.wallet.automation

import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.wallet.UserWalletManager
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class OffboardUsersTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[OffboardUsersTrigger.UserToOffboard] {

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[OffboardUsersTrigger.UserToOffboard]] = {
    walletManager.store
      .listUsersWithArchivedWalletInstalls(
        walletManager.listUsers,
        limit = 10,
      )
      .map(_.map(OffboardUsersTrigger.UserToOffboard(_)))
  }

  override protected def completeTask(task: OffboardUsersTrigger.UserToOffboard)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      _ <- Future {
        walletManager.offboardUser(task.username)
      }
      party <- connection.getPrimaryParty(task.username)
      _ <- connection.revokeUserRights(
        walletManager.validatorUser,
        Seq(party),
        Seq(party),
      )
    } yield TaskSuccess(s"offboarded user ${task.username} from wallet")
  }

  override protected def isStaleTask(task: OffboardUsersTrigger.UserToOffboard)(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    Future.successful(walletManager.lookupUserWallet(task.username).isEmpty)
  }
}

object OffboardUsersTrigger {
  case class UserToOffboard(
      username: String
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] = {
      prettyOfString(u => s"Offboard user ${u.username}")
    }
  }
}
