package com.daml.network.sv.automation.leaderbased

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.AssignedContract

import com.digitalasset.canton.lifecycle.UnlessShutdown
import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Random

trait SvTaskBasedTrigger[T <: PrettyPrinting] { this: TaskbasedTrigger[T] =>
  protected implicit def ec: ExecutionContext
  protected def svTaskContext: SvTaskBasedTrigger.Context
  protected def enableAutomaticLeaderElection: Boolean = false
  private val store = svTaskContext.svcStore

  final protected override def completeTask(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      svcRules <- store.getSvcRules()
      sameEpoch = svcRules.payload.epoch == svTaskContext.epoch
      isLeader = svcRules.payload.leader == store.key.svParty.toProtoPrimitive
      result <-
        if (sameEpoch) {
          if (isLeader) {
            completeTaskAsLeader(task)
          } else {
            monitorTaskAsFollower(task)
          }
        } else {
          // TODO(#6856) Could this be busy-looping as well, if we are a polling trigger?
          Future.successful(
            TaskSuccess(
              s"Skipping because current epoch ${svcRules.payload.epoch} is not the same as trigger registration epoch ${svTaskContext.epoch}"
            )
          )
        }
    } yield result
  }

  /** Handle leader failure by voting for a new leader
    */
  final protected def voteForNewLeader(
      svcRules: AssignedContract[cn.svcrules.SvcRules.ContractId, cn.svcrules.SvcRules],
      currentLeader: String,
  )(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    for {
      queryResult <- store.lookupElectionRequestByRequesterWithOffset(
        store.key.svParty,
        svTaskContext.epoch,
      )
      retVal <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"already voted in an election for epoch ${svTaskContext.epoch} to replace inactive leader ${currentLeader}"
            )
          )
        case QueryResult(offset, None) => {
          val self = store.key.svParty.toProtoPrimitive
          val otherParties =
            svcRules.payload.members.keySet.asScala.to(Set) - currentLeader - self
          val ranking = self :: Random.shuffle(otherParties.toList) ++ List(currentLeader)
          val cmd = svcRules.exercise(
            _.exerciseSvcRules_RequestElection(
              self,
              new cn.svcrules.electionrequestreason.ERR_LeaderUnavailable(
                com.daml.ledger.javaapi.data.Unit.getInstance()
              ),
              ranking.asJava,
            )
          )
          svTaskContext.connection
            .submit(
              actAs = Seq(store.key.svParty),
              readAs = Seq(store.key.svcParty),
              update = cmd,
            )
            .withDedup(
              commandId = CNLedgerConnection.CommandId(
                "com.daml.network.sv.requestElection",
                Seq(store.key.svParty, store.key.svcParty),
                svTaskContext.epoch.toString,
              ),
              deduplicationOffset = offset,
            )
            .yieldUnit()
            .map(_ => {
              TaskSuccess(
                s"successfully requested an election to replace inactive leader ${currentLeader}"
              )
            })
        }
      }
    } yield retVal
  }

  protected def completeTaskAsLeader(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome]

  final protected def monitorTaskAsFollower(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {

    logger.debug(s"Starting check for leader inactivity")
    for {
      svcRules <- store.getSvcRules()
      monitoredEpoch = svcRules.payload.epoch
      monitoredLeader = svcRules.payload.leader
      timer <- context.retryProvider
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
      result <- timer match {
        case UnlessShutdown.AbortedDueToShutdown =>
          Future.successful(
            TaskSuccess(
              s"stopping the check for leader inactivity, as the trigger is being shut down"
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
            if (isLeaderInactive && svcRules.payload.epoch != svTaskContext.epoch) {
              Future.successful(
                TaskSuccess(
                  s"skipping vote to replace leader $monitoredLeader because current epoch ${svcRules.payload.epoch} is not the same as trigger registration epoch ${svTaskContext.epoch}"
                )
              )
            } else if (isLeaderInactive) {
              // TODO(#6856) Resolve the busy loop in a more elegant way.
              if (enableAutomaticLeaderElection) {
                voteForNewLeader(svcRules, monitoredLeader)
              } else {
                Future.successful(
                  TaskSuccess(
                    s"skipping vote to replace leader $monitoredLeader because this trigger is configured to not trigger votes"
                  )
                )
              }
            } else {
              Future.successful(
                TaskSuccess(
                  s"Leader inactivity check completed, leader is active"
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
