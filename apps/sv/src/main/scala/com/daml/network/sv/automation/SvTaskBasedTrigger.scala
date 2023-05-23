package com.daml.network.sv.automation

import com.daml.network.automation.*
import com.daml.network.codegen.java.cn
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.util.Random

trait SvTaskBasedTrigger[T] {
  protected implicit def ec: ExecutionContext
  protected def svTaskContext: SvTaskBasedTrigger.Context

  final protected def completeTask(
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
            completeTaskAsFollower(task)
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
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
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

  protected def completeTaskAsFollower(
      task: T
  )(implicit tc: TraceContext): Future[TaskOutcome]
}

object SvTaskBasedTrigger {
  case class Context(
      svcStore: SvSvcStore,
      connection: CNLedgerConnection,
      leader: PartyId,
      epoch: Long,
  )
}
