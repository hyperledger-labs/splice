package com.daml.network.sv.automation

import cats.data.OptionT
import cats.instances.future.*
import com.daml.network.automation.{ScheduledTaskTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.{cc, cn}
import com.daml.network.environment.CNLedgerConnection
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
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends ScheduledTaskTrigger[AdvanceOpenMiningRoundTrigger.Task]
    with SvTaskBasedTrigger[ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]] {

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
      (offset, _) <- connection.submitWithResultAndOffsetNoDedup(
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
      .map(_.payload.leader)
      .flatMap(monitoredLeader => {

        val continueOrShutdownSignal = context.retryProvider.waitUnlessShutdown(
          context.clock
            .scheduleAfter(
              _ => {
                val isLeaderInactive = for {
                  currentLeader <- store
                    .getSvcRules()
                    .map(_.payload.leader == monitoredLeader)
                  taskStale <- isStaleTask(task)
                } yield currentLeader && !taskStale

                isLeaderInactive.foreach(isInactive => {
                  if (isInactive) {
                    handleLeaderFailure(
                      monitoredLeader,
                      show"The leader is inactive for ${task.work}",
                    )
                  }
                })
              },
              context.config.leaderInactiveTimeout.asJava
                .plus(context.config.pollingInterval.asJava),
            )
        )
        continueOrShutdownSignal.unwrap.flatMap {
          case UnlessShutdown.AbortedDueToShutdown =>
            Future.successful(
              TaskSuccess(
                show"Shutting down leader inactivity check for ${task.work}"
              )
            )
          case UnlessShutdown.Outcome(()) =>
            Future.successful(
              TaskSuccess(
                show"Leader inactivity check completed for ${task.work}"
              )
            )
        }

      })
  }

  override protected def isStaleTask(
      task: ScheduledTaskTrigger.ReadyTask[AdvanceOpenMiningRoundTrigger.Task]
  )(implicit tc: TraceContext): Future[Boolean] = {
    import cats.data.OptionT
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

  def handleLeaderFailure(currentLeader: String, msg: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    logger.warn(msg)

    import scala.jdk.CollectionConverters.*
    import scala.util.Random

    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      self = store.key.svParty.toProtoPrimitive
      otherParties = Set.empty ++ svcRules.payload.members.keySet.asScala - currentLeader - self
      ranking = self :: Random.shuffle(otherParties.toList) ++ List(currentLeader)
      cmd = svcRules.contractId.exerciseSvcRules_RequestElection(
        self,
        new cn.svcrules.electionrequestreason.ERR_LeaderUnavailable(
          com.daml.ledger.javaapi.data.Unit.getInstance()
        ),
        ranking.asJava,
      )
      (offset, _) <- connection.submitWithResultAndOffsetNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        cmd,
        domainId = domainId,
      )
    } yield {
      TaskSuccess(
        s"Successfully requested an election to replace inactive leader ${currentLeader}"
      )
    }
  }

  override protected def isLeader()(implicit tc: TraceContext): Future[Boolean] = store.svIsLeader()
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
