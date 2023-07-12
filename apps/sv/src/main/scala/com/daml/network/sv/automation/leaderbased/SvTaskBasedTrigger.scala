package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Random

trait SvTaskBasedTrigger[T <: PrettyPrinting] { this: TaskbasedTrigger[T] =>
  protected implicit def ec: ExecutionContext
  protected def svTaskContext: SvTaskBasedTrigger.Context

  final protected override def completeTask(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    svTaskContext.svcStore
      .getSvcRules()
      .flatMap(svcRules => {
        val sameEpoch = svcRules.payload.epoch == svTaskContext.epoch
        val isLeader =
          svcRules.payload.leader == svTaskContext.svcStore.key.svParty.toProtoPrimitive
        if (sameEpoch) {
          if (isLeader) {
            completeTaskAsLeader(task)
          } else {
            monitorTaskAsFollower(task)
          }
        } else {
          Future.successful(
            TaskSuccess(
              s"Skipping because current epoch ${svcRules.payload.epoch} is not the same as trigger registration epoch ${svTaskContext.epoch}"
            )
          )
        }
      })
  }

  /** Handle leader failure by voting for a new leader
    */
  final protected def voteForNewLeader(currentLeader: String)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {

    val store = svTaskContext.svcStore

    for {
      domainId <- store.domains.waitForDomainConnection(store.defaultAcsDomain)
      svcRules <- store.getSvcRules()
      currentRequesters <- store.listElectionRequests(svcRules).map(_.map(_.payload.requester))
      self = store.key.svParty.toProtoPrimitive
      otherParties = svcRules.payload.members.keySet.asScala.to(Set) - currentLeader - self
      ranking = self :: Random.shuffle(otherParties.toList) ++ List(currentLeader)
      cmd = svcRules.contractId.exerciseSvcRules_RequestElection(
        self,
        new cn.svcrules.electionrequestreason.ERR_LeaderUnavailable(
          com.daml.ledger.javaapi.data.Unit.getInstance()
        ),
        ranking.asJava,
      )

      retVal <-
        if (svcRules.payload.epoch != svTaskContext.epoch) {
          Future.successful(
            TaskSuccess(
              s"Skipping vote to replace leader $currentLeader because current epoch ${svcRules.payload.epoch} is not the same as trigger registration epoch ${svTaskContext.epoch}"
            )
          )
        } else if (!currentRequesters.contains(self)) {
          // TODO(#4846) Use command deduplication to avoid crossing inflight requests for SvcRules_RequestElection
          svTaskContext.connection
            .submitWithResultAndOffsetNoDedup(
              Seq(store.key.svParty),
              Seq(store.key.svcParty),
              cmd,
              domainId = domainId,
            )
            .map(_ => {
              TaskSuccess(
                s"Successfully requested an election to replace inactive leader ${currentLeader}"
              )
            })
        } else {
          Future.successful(
            TaskSuccess(
              s"Already voted in an election this epoch to replace inactive leader ${currentLeader}"
            )
          )
        }
    } yield retVal
  }

  protected def completeTaskAsLeader(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome]

  private val store = svTaskContext.svcStore

  final protected def monitorTaskAsFollower(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    logger.debug(show"Starting check for leader inactivity")
    for {
      svcRules <- store.getSvcRules()
      monitoredEpoch = svcRules.payload.epoch
      monitoredLeader = svcRules.payload.leader
      timer = context.retryProvider
        .waitUnlessShutdown(
          context.clock
            .scheduleAfter(
              _ => {
                // No work done here, as we are only interested in the scheduling notification
                ()
              },
              // NOTE: We don't restart existing inactivity checks when the leaderInactiveTimeout changes
              SvUtil
                .fromRelTime(svcRules.payload.config.leaderInactiveTimeout)
                .plus(context.config.pollingInterval.asJava),
            )
        )
        .unwrap
      result <- timer.flatMap {
        case UnlessShutdown.AbortedDueToShutdown =>
          Future.successful(
            TaskSuccess(
              show"stopping the check for leader inactivity, as the trigger is being shut down"
            )
          )
        case UnlessShutdown.Outcome(()) => {
          val isLeaderInactiveF = for {
            sameEpoch <- store
              .getSvcRules()
              .map(_.payload.epoch == monitoredEpoch)
            taskStale <- isStaleTask(task)
          } yield sameEpoch && !taskStale

          isLeaderInactiveF.flatMap(isLeaderInactive => {
            if (isLeaderInactive) {
              voteForNewLeader(
                monitoredLeader
              )
            } else {
              Future.successful(
                TaskSuccess(
                  show"Leader inactivity check completed, leader is active"
                )
              )
            }
          })
        }
      }
    } yield result
  }
}

object SvTaskBasedTrigger {
  case class Context(
      svcStore: SvSvcStore,
      connection: CNLedgerConnection,
      leader: PartyId,
      epoch: Long,
  )
}
