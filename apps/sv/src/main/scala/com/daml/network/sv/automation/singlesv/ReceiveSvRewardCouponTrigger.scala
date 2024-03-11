package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svc.memberstate.MemberRewardState
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.sv.store.SvSvcStore.OpenMiningRoundContract
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.*

class ReceiveSvRewardCouponTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    cnLedgerConnection: CNLedgerConnection,
    extraBeneficiaries: Map[PartyId, BigDecimal],
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ReceiveSvRewardCouponTrigger.Task] {

  private val svParty = store.key.svParty
  private val svcParty = store.key.svcParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ReceiveSvRewardCouponTrigger.Task]] = {
    retrieveNextRoundToClaim().value.map(_.toList)
  }

  private def retrieveNextRoundToClaim()(implicit
      tc: TraceContext
  ): OptionT[Future, ReceiveSvRewardCouponTrigger.Task] = {
    for {
      // Note that the SvcRules will be different for every task, so we have to return them one-by-one.
      svcRules <- OptionT.liftF(store.getSvcRules())
      memberInfo <- OptionT.fromOption[Future](
        Option(svcRules.payload.members.get(svParty.toProtoPrimitive))
      )
      rewardState <- OptionT(store.lookupMemberRewardState(memberInfo.name))
      openRounds <- OptionT.liftF(store.getOpenMiningRoundTriple())
      lastReceivedForOpt = memberLastReceivedFor(rewardState.payload)
      firstOpenNotClaimed <- OptionT.fromOption[Future](
        openRounds.toSeq
          .filter(round =>
            round.payload.opensAt <= context.clock.now.toInstant
              && lastReceivedForOpt.forall(_ < round.payload.round.number)
          )
          .minByOption(_.payload.opensAt)
      )
    } yield ReceiveSvRewardCouponTrigger.Task(
      svcRules,
      memberInfo.svRewardWeight,
      rewardState,
      firstOpenNotClaimed,
    )
  }

  private def memberLastReceivedFor(rewardState: MemberRewardState): Option[Long] = {
    // -1 is the value set in SvcRules_ConfirmSvOnboarding for new SVs
    Option(rewardState.state.lastRoundCollected.number.longValue()).filter(_ > -1)
  }

  override protected def completeTask(task: ReceiveSvRewardCouponTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val ReceiveSvRewardCouponTrigger.Task(svcRules, svRewardWeight, rewardState, unclaimedRound) =
      task
    val lastReceivedForOpt = memberLastReceivedFor(rewardState.payload)
    lastReceivedForOpt match {
      case None =>
        logger.info(
          s"SV never received SV rewards, it will start now at round ${unclaimedRound.payload.round.number}."
        )
      case Some(lastReceivedFor) =>
        val skippedCount = unclaimedRound.payload.round.number - lastReceivedFor - 1
        if (skippedCount > 0)
          logger.warn(
            s"Skipped $skippedCount SV rewards from last claimed round $lastReceivedFor to current round ${unclaimedRound.payload.round.number}. " +
              s"This is expected in case of SV inactivity."
          )
    }
    val weightDistribution =
      SvUtil.weightDistributionForSv(svRewardWeight, extraBeneficiaries, svParty)(logger, tc)
    cnLedgerConnection
      .submit(
        actAs = Seq(svParty),
        readAs = Seq(svcParty),
        svcRules
          .exercise(
            _.exerciseSvcRules_ReceiveSvRewardCoupon(
              svParty.toProtoPrimitive,
              unclaimedRound.contractId,
              rewardState.contractId,
              weightDistribution
                .map { case (party, weight) =>
                  new Tuple2[String, java.lang.Long](
                    party.toProtoPrimitive,
                    weight,
                  )
                }
                .toList
                .asJava,
            )
          ),
      )
      .noDedup
      .yieldUnit()
      .map(_ => TaskSuccess(s"Received SV reward for Round ${unclaimedRound.payload.round.number}"))
  }

  override protected def isStaleTask(
      task: ReceiveSvRewardCouponTrigger.Task
  )(implicit tc: TraceContext): Future[Boolean] = {
    val nextRound = retrieveNextRoundToClaim()
    nextRound.forall(_ != task)
  }

}

object ReceiveSvRewardCouponTrigger {

  case class Task(
      svcRules: AssignedContract[SvcRules.ContractId, SvcRules],
      svRewardWeight: Long,
      rewardState: AssignedContract[MemberRewardState.ContractId, MemberRewardState],
      round: OpenMiningRoundContract,
  ) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("svcRulesCid", _.svcRules.contractId),
        param("svRewardWeight", _.svRewardWeight),
        param("rewardState", _.rewardState),
        param("round", _.round),
      )
  }

}
