package com.daml.network.svc.automation

import cats.data.OptionT
import cats.instances.future.*
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.config.SvcAppBackendConfig
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AdvanceOpenMiningRoundTrigger(
    override protected val context: TriggerContext,
    svcAppConfig: SvcAppBackendConfig,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[AdvanceOpenMiningRoundTrigger.Task] {

  /** Retrieve a batch of tasks that are ready for execution now. */
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AdvanceOpenMiningRoundTrigger.Task]] =
    (for {
      rules <- OptionT(store.lookupCoinRules())
      rounds <- OptionT(store.lookupOpenMiningRoundTriple())
      if (rounds.readyToAdvanceAt.isBefore(now.toInstant))
      // NOTE: we store the coin-rules reference in the task, as otherwise its tickDuration and the one that is
      // actually used in the choice might go out of sync
    } yield AdvanceOpenMiningRoundTrigger.Task(rules.contractId, rounds)).value.map(_.toList)

  /** How to process a task. */
  override protected def completeTask(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val rounds = task.work.openRounds
    val cmds = task.work.coinRulesId
      .exerciseCoinRules_AdvanceOpenMiningRounds(
        svcAppConfig.coinPrice.bigDecimal,
        rounds.oldest.contractId,
        rounds.middle.contractId,
        rounds.newest.contractId,
      )
      .commands
      .asScala
      .toSeq
    store.domains.getUniqueDomainId().flatMap { domainId =>
      connection
        .submitCommandsNoDedupTransaction(Seq(store.svcParty), Seq(), cmds, domainId)
        .flatMap(tx =>
          // make sure the store ingested our update so we don't
          // attempt to advance the same round twice
          store.acs.signalWhenIngested(tx.getOffset())
        )
        .map(_ =>
          TaskSuccess(
            s"successfully advanced the rounds and archived round ${rounds.oldest.payload.round.number}"
          )
        )
    }
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] = {
    import cats.data.OptionT
    import cats.instances.future.*
    import cats.syntax.traverse.*

    (for {
      _ <- OptionT(store.acs.lookupContractById(cc.coin.CoinRules.COMPANION)(task.work.coinRulesId))
      _ <- task.work.openRounds.toSeq.traverse(co =>
        OptionT(store.acs.lookupContractById(cc.round.OpenMiningRound.COMPANION)(co.contractId))
      )
    } yield ()).isEmpty
  }
}

object AdvanceOpenMiningRoundTrigger {

  case class Task(
      coinRulesId: cc.coin.CoinRules.ContractId,
      openRounds: SvcStore.OpenMiningRoundTriple,
  ) extends PrettyPrinting {

    import com.daml.network.util.PrettyInstances.*

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("coinRulesId", _.coinRulesId), param("openRounds", _.openRounds))
  }
}
