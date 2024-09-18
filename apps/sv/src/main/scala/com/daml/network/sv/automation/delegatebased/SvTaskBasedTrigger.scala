// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.delegatebased

import com.daml.network.automation.*
import com.daml.network.codegen.java.splice
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.store.MultiDomainAcsStore.QueryResult
import com.daml.network.sv.store.SvDsoStore
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
  protected def enableAutomaticDsoDelegateElection: Boolean = false
  private val store = svTaskContext.dsoStore

  final protected override def completeTask(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    for {
      dsoRules <- store.getDsoRules()
      sameEpoch = dsoRules.payload.epoch == svTaskContext.epoch
      isLeader = dsoRules.payload.dsoDelegate == store.key.svParty.toProtoPrimitive
      result <-
        if (sameEpoch) {
          if (isLeader) {
            completeTaskAsDsoDelegate(task)
          } else {
            monitorTaskAsFollower(task)
          }
        } else {
          // TODO(#6856) Could this be busy-looping as well, if we are a polling trigger?
          Future.successful(
            TaskSuccess(
              s"Skipping because current epoch ${dsoRules.payload.epoch} is not the same as trigger registration epoch ${svTaskContext.epoch}"
            )
          )
        }
    } yield result
  }

  /** Handle delegate failure by voting for a new delegate
    */
  final protected def voteForNewDsoDelegate(
      dsoRules: AssignedContract[splice.dsorules.DsoRules.ContractId, splice.dsorules.DsoRules],
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
              s"already voted in an election for epoch ${svTaskContext.epoch} to replace inactive delegate ${currentLeader}"
            )
          )
        case QueryResult(offset, None) => {
          val self = store.key.svParty.toProtoPrimitive
          val otherParties =
            dsoRules.payload.svs.keySet.asScala.to(Set) - currentLeader - self
          val ranking = self :: Random.shuffle(otherParties.toList) ++ List(currentLeader)
          val cmd = dsoRules.exercise(
            _.exerciseDsoRules_RequestElection(
              self,
              new splice.dsorules.electionrequestreason.ERR_DsoDelegateUnavailable(
                com.daml.ledger.javaapi.data.Unit.getInstance()
              ),
              ranking.asJava,
            )
          )
          svTaskContext.connection
            .submit(
              actAs = Seq(store.key.svParty),
              readAs = Seq(store.key.dsoParty),
              update = cmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "com.daml.network.sv.requestElection",
                Seq(store.key.svParty, store.key.dsoParty),
                svTaskContext.epoch.toString,
              ),
              deduplicationOffset = offset,
            )
            .yieldUnit()
            .map(_ => {
              TaskSuccess(
                s"successfully requested an election to replace inactive delegate ${currentLeader}"
              )
            })
        }
      }
    } yield retVal
  }

  protected def completeTaskAsDsoDelegate(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome]

  final protected def monitorTaskAsFollower(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome] = {

    logger.debug(s"Starting check for delegate inactivity")
    for {
      dsoRules <- store.getDsoRules()
      monitoredEpoch = dsoRules.payload.epoch
      monitoredLeader = dsoRules.payload.dsoDelegate
      timer <- context.retryProvider
        .waitUnlessShutdown(
          context.clock
            .scheduleAfter(
              _ => {
                // No work done here, as we are only interested in the scheduling notification
                ()
              },
              // NOTE: We don't restart existing inactivity checks when the dsoDelegateInactiveTimeout changes
              SvUtil
                .fromRelTime(dsoRules.payload.config.dsoDelegateInactiveTimeout)
                .plus(context.config.pollingInterval.asJava),
            )
        )
        .unwrap
      result <- timer match {
        case UnlessShutdown.AbortedDueToShutdown =>
          Future.successful(
            TaskSuccess(
              s"stopping the check for delegate inactivity, as the trigger is being shut down"
            )
          )
        case UnlessShutdown.Outcome(()) => {
          val isLeaderInactiveF = for {
            sameEpoch <- store
              .getDsoRules()
              .map(_.payload.epoch == monitoredEpoch)
            taskStale <- isStaleTask(task)
          } yield sameEpoch && !taskStale

          isLeaderInactiveF.flatMap(isLeaderInactive => {
            if (isLeaderInactive && dsoRules.payload.epoch != svTaskContext.epoch) {
              Future.successful(
                TaskSuccess(
                  s"skipping vote to replace delegate $monitoredLeader because current epoch ${dsoRules.payload.epoch} is not the same as trigger registration epoch ${svTaskContext.epoch}"
                )
              )
            } else if (isLeaderInactive) {
              // TODO(#6856) Resolve the busy loop in a more elegant way.
              if (enableAutomaticDsoDelegateElection) {
                voteForNewDsoDelegate(dsoRules, monitoredLeader)
              } else {
                Future.successful(
                  TaskSuccess(
                    s"skipping vote to replace delegate $monitoredLeader because this trigger is configured to not trigger votes"
                  )
                )
              }
            } else {
              Future.successful(
                TaskSuccess(
                  s"Leader inactivity check completed, delegate is active"
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
      dsoStore: SvDsoStore,
      connection: SpliceLedgerConnection,
      dsoDelegate: PartyId,
      epoch: Long,
  )
}
