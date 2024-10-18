// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.automation

import com.daml.ledger.javaapi.data.codegen.ContractId
import org.lfdecentralizedtrust.splice.automation.TransferFollowTrigger.LubTask
import org.lfdecentralizedtrust.splice.environment.SpliceLedgerConnection
import org.lfdecentralizedtrust.splice.environment.ledger.api.LedgerClient
import org.lfdecentralizedtrust.splice.store.AppStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}

/** Trigger that submits transfers to make contracts "follow" another contract, e.g.,
  * splitwell BalanceUpdates follow the corresponding Group contracts.
  */
class TransferFollowTrigger(
    override protected val context: TriggerContext,
    store: AppStore,
    connection: SpliceLedgerConnection,
    partyId: PartyId,
    retrieve: TraceContext => Future[Seq[LubTask]],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[
      LubTask
    ] {

  override protected def extraMetricLabels = Seq("party" -> partyId.toString)

  override def retrieveTasks()(implicit tc: TraceContext) = retrieve(tc)

  override protected def completeTask(
      task: LubTask
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    require(task.leader.domain != task.follower.domain)
    val leaderCid = PrettyContractId(task.leader.contract)
    val followerCid = PrettyContractId(task.follower.contract)
    for {
      _ <- connection.submitReassignmentAndWaitNoDedup(
        submitter = partyId,
        command = LedgerClient.ReassignmentCommand.Unassign(
          contractId = task.follower.contractId,
          source = task.follower.domain,
          target = task.leader.domain,
        ),
      )
    } yield TaskSuccess(
      show"Submitted unassign of $followerCid from ${task.follower.domain} to ${task.leader.domain} of $leaderCid"
    )
  }

  override protected def isStaleTask(
      task: LubTask
  )(implicit tc: TraceContext): Future[Boolean] =
    isContractAbsentFromDomain(
      task.leader.domain,
      task.leader.contractId,
    )
      .flatMap { leaderAbsent =>
        if (leaderAbsent) {
          Future.successful(true)
        } else {
          isContractAbsentFromDomain(
            task.follower.domain,
            task.follower.contractId,
          )
        }
      }

  private[this] def isContractAbsentFromDomain(domain: DomainId, id: ContractId[?])(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState.*
    store.multiDomainAcsStore
      .lookupContractStateById(id)
      .map {
        case Some(Assigned(`domain`)) => false
        case None | Some(Assigned(_) | InFlight) => true
      }
  }
}

object TransferFollowTrigger {
  type LubTask = Task[?, ?, ?, ?]

  final case class Task[LeaderTCid, LeaderT, FollowerTCid, FollowerT](
      leader: AssignedContract[LeaderTCid, LeaderT],
      follower: AssignedContract[FollowerTCid, FollowerT],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("leader", _.leader),
        param("follower", _.follower),
      )
  }
}
