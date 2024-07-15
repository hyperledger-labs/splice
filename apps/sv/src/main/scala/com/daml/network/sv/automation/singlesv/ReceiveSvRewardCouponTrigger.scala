// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation.singlesv

import cats.data.OptionT
import com.daml.network.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dso.svstate.SvRewardState
import com.daml.network.codegen.java.splice.dsorules.DsoRules
import com.daml.network.codegen.java.da.types.Tuple2
import com.daml.network.environment.SpliceLedgerConnection
import com.daml.network.sv.config.BeneficiaryConfig
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.store.MiningRoundsStore.OpenMiningRoundContract
import com.daml.network.sv.util.SvUtil
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.*

class ReceiveSvRewardCouponTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    spliceLedgerConnection: SpliceLedgerConnection,
    extraBeneficiaries: Seq[BeneficiaryConfig],
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    override val tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[ReceiveSvRewardCouponTrigger.Task] {

  override def isRewardOperationTrigger: Boolean = true

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty

  override protected def retrieveTasks()(implicit
      tc: TraceContext
  ): Future[Seq[ReceiveSvRewardCouponTrigger.Task]] = {
    retrieveNextRoundToClaim().value.map(_.toList)
  }

  private def retrieveNextRoundToClaim()(implicit
      tc: TraceContext
  ): OptionT[Future, ReceiveSvRewardCouponTrigger.Task] = {
    for {
      // Note that the DsoRules will be different for every task, so we have to return them one-by-one.
      dsoRules <- OptionT.liftF(store.getDsoRules())
      svInfo <- OptionT.fromOption[Future](
        Option(dsoRules.payload.svs.get(svParty.toProtoPrimitive))
      )
      rewardState <- OptionT(store.lookupSvRewardState(svInfo.name))
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
      dsoRules,
      svInfo.svRewardWeight,
      rewardState,
      firstOpenNotClaimed,
    )
  }

  private def memberLastReceivedFor(rewardState: SvRewardState): Option[Long] = {
    // -1 is the value set in DsoRules_ConfirmSvOnboarding for new SVs
    Option(rewardState.state.lastRoundCollected.number.longValue()).filter(_ > -1)
  }

  override protected def completeTask(task: ReceiveSvRewardCouponTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val ReceiveSvRewardCouponTrigger.Task(dsoRules, svRewardWeight, rewardState, unclaimedRound) =
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
    spliceLedgerConnection
      .submit(
        actAs = Seq(svParty),
        readAs = Seq(dsoParty),
        dsoRules
          .exercise(
            _.exerciseDsoRules_ReceiveSvRewardCoupon(
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
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
      svRewardWeight: Long,
      rewardState: AssignedContract[SvRewardState.ContractId, SvRewardState],
      round: OpenMiningRoundContract,
  ) extends PrettyPrinting {
    import com.daml.network.util.PrettyInstances.*
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("dsoRulesCid", _.dsoRules.contractId),
        param("svRewardWeight", _.svRewardWeight),
        param("rewardState", _.rewardState),
        param("round", _.round),
      )
  }

}
