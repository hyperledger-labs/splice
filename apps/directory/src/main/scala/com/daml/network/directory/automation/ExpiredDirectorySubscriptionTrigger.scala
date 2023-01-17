package com.daml.network.directory.automation

import cats.data.OptionT
import cats.instances.future.*
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.subscriptions as subsCodegen
import com.daml.network.codegen.java.cn.directory as directoryCodegen
import com.daml.network.directory.store.DirectoryStore
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpiredDirectorySubscriptionTrigger(
    override protected val context: TriggerContext,
    store: DirectoryStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[DirectoryStore.IdleDirectorySubscription] {

  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[DirectoryStore.IdleDirectorySubscription]] =
    store.listExpiredDirectorySubscriptions(now, limit)

  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[DirectoryStore.IdleDirectorySubscription]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val cmd = task.work.state.contractId.exerciseSubscriptionIdleState_ExpireSubscription(
      store.providerParty.toProtoPrimitive
    )
    store.domains.getUniqueDomainId().flatMap { domainId =>
      connection
        .submitCommandsNoDedup(
          actAs = Seq(store.providerParty),
          readAs = Seq(),
          commands = cmd.commands.asScala.toSeq,
          domainId = domainId,
        )
        .map(_ => TaskSuccess(s"archived expired directory subscription"))
    }
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[DirectoryStore.IdleDirectorySubscription]
  )(implicit tc: TraceContext): Future[Boolean] =
    (for {
      _ <- OptionT(
        store.acs.lookupContractById(subsCodegen.SubscriptionIdleState.COMPANION)(
          task.work.state.contractId
        )
      )
      _ <- OptionT(
        store.acs.lookupContractById(directoryCodegen.DirectoryEntryContext.COMPANION)(
          task.work.context.contractId
        )
      )
    } yield ()).isEmpty
}
