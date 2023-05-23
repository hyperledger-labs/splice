package com.daml.network.sv.automation

import cats.data.OptionT
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class AdvanceOpenMiningRoundTrigger(
    override protected val context: TriggerContext,
    override protected val svTaskContext: SvTaskBasedTrigger.Context,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[AdvanceOpenMiningRoundTrigger.Task]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]] {

  private val store = svTaskContext.svcStore

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
  override protected def completeTaskAsLeader(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val rounds = task.work.openRounds
    store
      .getSvcRules()
      .foreach(svcRules =>
        logger.debug(s"Starting work as leader ${svcRules.payload.leader} for ${task.work}")
      )
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      coinPriceVotes <- store.listMemberCoinPriceVotes()
      cmd = svcRules.contractId.exerciseSvcRules_AdvanceOpenMiningRounds(
        task.work.coinRulesId,
        rounds.oldest.contractId,
        rounds.middle.contractId,
        rounds.newest.contractId,
        coinPriceVotes.map(_.contractId).asJava,
      )
      (offset, _) <- svTaskContext.connection.submitWithResultAndOffsetNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        cmd,
        domainId = domainId,
      )
      // make sure the store ingested our update so we don't
      // attempt to advance the same round twice
      _ <- store.multiDomainAcsStore.signalWhenIngestedOrShutdown(domainId, offset)
    } yield TaskSuccess(
      s"successfully advanced the rounds and archived round ${rounds.oldest.payload.round.number}"
    )
  }

  override def completeTaskAsFollower(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    logger.debug(show"Starting check for leader inactivity for ${task.work}")
    store
      .getSvcRules()
      .flatMap(rules => {
        val (monitoredEpoch, monitoredLeader) = (rules.payload.epoch, rules.payload.leader)
        context.retryProvider
          .waitUnlessShutdown(
            context.clock
              .scheduleAfter(
                _ => {
                  // No work done here, as we are only interested in the scheduling notification
                  ()
                },
                context.config.effectiveLeaderInactiveTimeout.asJava,
              )
          )
          .unwrap
          .flatMap {
            case UnlessShutdown.AbortedDueToShutdown =>
              Future.successful(
                TaskSuccess(
                  show"Shutting down leader inactivity check for ${task.work}"
                )
              )
            case UnlessShutdown.Outcome(()) => {
              val isLeaderInactive = for {
                sameEpoch <- store
                  .getSvcRules()
                  .map(_.payload.epoch == monitoredEpoch)
                taskStale <- isStaleTask(task)
              } yield sameEpoch && !taskStale

              isLeaderInactive.flatMap(isInactive => {
                if (isInactive) {
                  voteForNewLeader(
                    monitoredLeader
                  )
                } else {
                  Future.successful(
                    TaskSuccess(
                      show"Leader inactivity check completed for ${task.work}"
                    )
                  )
                }
              })
            }
          }

      })
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] = {
    import cats.instances.future.*
    import cats.syntax.traverse.*

    (for {
      domainId <- OptionT liftF store.domains.signalWhenConnected(store.defaultAcsDomain)
      _ <- OptionT(
        store.multiDomainAcsStore
          .lookupContractByIdOnDomain(cc.coin.CoinRules.COMPANION)(domainId, task.work.coinRulesId)
      )
      _ <- task.work.openRounds.toSeq.traverse(co =>
        OptionT(
          store.multiDomainAcsStore
            .lookupContractByIdOnDomain(cc.round.OpenMiningRound.COMPANION)(domainId, co.contractId)
        )
      )
    } yield ()).isEmpty
  }
}

object AdvanceOpenMiningRoundTrigger {
  case class Task(
      coinRulesId: cc.coin.CoinRules.ContractId,
      openRounds: SvSvcStore.OpenMiningRoundTriple,
  ) extends PrettyPrinting {

    import com.daml.network.util.PrettyInstances.*

    override def pretty: Pretty[this.type] =
      prettyOfClass(param("coinRulesId", _.coinRulesId), param("openRounds", _.openRounds))
  }
}
