package com.daml.network.svc.automation

import com.daml.network.automation.{ScheduledTaskTrigger, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.CoinUtil
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AdvanceOpenMiningRoundTrigger(
    override protected val context: TriggerContext,
    svcAppConfig: LocalSvcAppConfig,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[AdvanceOpenMiningRoundTrigger.Task] {

  import com.daml.network.store.AcsStore.QueryResult

  /** Retrieve a batch of tasks that are ready for execution now. */
  override protected def listReadyTasks(now: CantonTimestamp, limit: Int)(implicit
      tc: TraceContext
  ): Future[Seq[AdvanceOpenMiningRoundTrigger.Task]] =
    for {
      QueryResult(_, optRules) <- store.lookupCoinRules()
      QueryResult(_, optRounds) <- store.lookupOpenMiningRoundTriple()
      result = for {
        // TODO(tech-debt): replace with usage of cats monad transformers, missing work: QueryResult should be a Monad or get rid of the wide use of QueryResult
        rules <- optRules
        rounds <- optRounds
        tickDuration = CoinUtil.relTimeToDuration(rules.payload.config.tickDuration)
        if (rounds.readyToAdvanceAt(tickDuration).isBefore(now.toInstant))
        // NOTE: we store the coin-rules reference in the task, as otherwise its tickDuration and the one that is actually used in the choice might go out of sync
      } yield AdvanceOpenMiningRoundTrigger.Task(rules.contractId, rounds)
    } yield result.toList

  /** How to process a task. */
  override protected def processTask(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[Option[String]] = {
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
    connection
      .submitCommandsNoDedup(Seq(store.svcParty), Seq(), cmds)
      .map(_ =>
        Some(
          s"successfully advanced the rounds and archived round ${rounds.oldest.payload.round.number}"
        )
      )
  }

  // TODO(tech-debt): this feels a bit duplicative to the above query. Consider exposing a PollingTrigger variant that just reruns the query on every retry
  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] =
    for {
      rules <- store.acs.lookupContractById(cc.coin.CoinRules.COMPANION)(task.work.coinRulesId)
      results <- Future.sequence(
        task.work.openRounds.toSeq.map(co =>
          store.acs.lookupContractById(cc.round.OpenMiningRound.COMPANION)(co.contractId)
        )
      )
    } yield rules.value.isEmpty || results.exists(_.value.isEmpty)
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
