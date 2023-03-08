package com.daml.network.wallet.automation

import com.daml.network.automation.TriggerContext
import com.daml.network.wallet.UserWalletManager
import scala.concurrent.ExecutionContext
import com.digitalasset.canton.tracing.TraceContext
import scala.concurrent.Future
import io.opentelemetry.api.trace.Tracer
import com.daml.network.automation.PollingParallelTaskExecutionTrigger
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.logging.pretty.Pretty
import com.daml.network.automation.TaskOutcome
import com.daml.network.automation.TaskSuccess

class OffboardUsersTrigger(
    override protected val context: TriggerContext,
    walletManager: UserWalletManager,
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
    Future {
      walletManager.offboardUser(task.username)
    }.map(_ => TaskSuccess(s"offboarded user ${task.username} from wallet"))
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
