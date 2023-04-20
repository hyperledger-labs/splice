package com.daml.network.automation

import com.daml.ledger.javaapi.data.codegen.{ContractTypeCompanion, ContractId}
import com.daml.network.automation.{TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.environment.ledger.api.LedgerClient
import com.daml.network.store.{CNNodeAppStore, MultiDomainAcsStore}
import MultiDomainAcsStore.ReadyContract
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

/** Trigger that submits transfers to make contracts "follow" another contract, e.g.,
  * splitwell BalanceUpdates follow the corresponding Group contracts.
  */
class TransferFollowTrigger[
    LeaderC <: ContractTypeCompanion[_, LeaderTCid, _, LeaderT],
    LeaderTCid <: ContractId[_],
    LeaderT,
    FollowerC <: ContractTypeCompanion[_, FollowerTCid, _, FollowerT],
    FollowerTCid <: ContractId[_],
    FollowerT,
](
    override protected val context: TriggerContext,
    store: CNNodeAppStore[_, _],
    connection: CNLedgerConnection,
    partyId: PartyId,
    leaderCompanion: LeaderC,
    followerCompanion: FollowerC,
    retrieve: () => Future[
      Seq[TransferFollowTrigger.Task[LeaderTCid, LeaderT, FollowerTCid, FollowerT]]
    ],
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
    leaderCompanionClass: MultiDomainAcsStore.ContractCompanion[LeaderC, LeaderTCid, LeaderT],
    followerCompanionClass: MultiDomainAcsStore.ContractCompanion[
      FollowerC,
      FollowerTCid,
      FollowerT,
    ],
) extends PollingParallelTaskExecutionTrigger[
      TransferFollowTrigger.Task[LeaderTCid, LeaderT, FollowerTCid, FollowerT]
    ] {

  override def retrieveTasks()(implicit tc: TraceContext) = retrieve()

  override protected def completeTask(
      task: TransferFollowTrigger.Task[LeaderTCid, LeaderT, FollowerTCid, FollowerT]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    require(task.leader.domain != task.follower.domain)
    val leaderCid = PrettyContractId(task.leader.contract)
    val followerCid = PrettyContractId(task.follower.contract)
    for {
      _ <- connection.submitTransferAndAwaitIngestionNoDedup(
        store.multiDomainAcsStore,
        submitter = partyId,
        command = LedgerClient.TransferCommand.Out(
          contractId = task.follower.contract.contractId,
          source = task.follower.domain,
          target = task.leader.domain,
        ),
      )
    } yield TaskSuccess(
      show"Submitted transfer out of $followerCid from ${task.follower.domain} to ${task.leader.domain} of $leaderCid"
    )
  }

  override protected def isStaleTask(
      task: TransferFollowTrigger.Task[LeaderTCid, LeaderT, FollowerTCid, FollowerT]
  )(implicit tc: TraceContext): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractByIdOnDomain(leaderCompanion)(
        task.leader.domain,
        task.leader.contract.contractId,
      )
      .flatMap { leaderO =>
        if (leaderO.isEmpty) {
          Future.successful(true)
        } else {
          store.multiDomainAcsStore
            .lookupContractByIdOnDomain(followerCompanion)(
              task.follower.domain,
              task.follower.contract.contractId,
            )
            .map(_.isEmpty)
        }
      }
}

object TransferFollowTrigger {
  final case class Task[LeaderTCid, LeaderT, FollowerTCid, FollowerT](
      leader: ReadyContract[LeaderTCid, LeaderT],
      follower: ReadyContract[FollowerTCid, FollowerT],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("leader", _.leader),
        param("follower", _.follower),
      )
  }
}
