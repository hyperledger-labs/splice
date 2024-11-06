// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.data.OptionT
import cats.implicits.toTraverseOps
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import cats.syntax.traverseFilter.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvRewardState
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.DsoRules
import org.lfdecentralizedtrust.splice.codegen.java.da.types.Tuple2
import org.lfdecentralizedtrust.splice.environment.{
  DarResources,
  ParticipantAdminConnection,
  SpliceLedgerConnection,
}
import org.lfdecentralizedtrust.splice.sv.config.BeneficiaryConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.store.MiningRoundsStore.OpenMiningRoundContract
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.util.{AmuletConfigSchedule, AssignedContract}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.collection.immutable.Seq
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*
import scala.math.Ordering.Implicits.*

class ReceiveSvRewardCouponTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    participantAdminConnection: ParticipantAdminConnection,
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
    for {
      dsoRules <- store.getDsoRules()
      amuletRules <- store.getAmuletRules()
      packages = AmuletConfigSchedule(amuletRules)
        .getConfigAsOf(context.clock.now)
        .packageConfig
      svLatestVettedPackages = Seq(
        DarResources.amulet.getPackageIdWithVersion(packages.amulet)
      )
      beneficiariesWithLatestVettedPackages <- extraBeneficiaries.filterA { beneficiary =>
        participantAdminConnection
          .getPartyToParticipant(
            dsoRules.domain,
            beneficiary.beneficiary,
          )
          .flatMap(partyToParticipant =>
            isVettingLatestPackages(
              partyToParticipant.mapping.participantIds,
              svLatestVettedPackages.flatMap(_.toList),
            )
          )
      }
      result <- retrieveNextRoundToClaim(beneficiariesWithLatestVettedPackages).value.map(_.toList)
    } yield {
      val beneficiariesWithoutLatestPackages =
        extraBeneficiaries.diff(beneficiariesWithLatestVettedPackages)
      if (beneficiariesWithoutLatestPackages.isEmpty) {
        logger.info(s"All beneficiaries vetted the latest packages.")
      } else {
        logger.warn(
          s"Beneficiaries did not vet the latest packages: $beneficiariesWithoutLatestPackages"
        )
      }
      result
    }
  }

  private def isVettingLatestPackages(
      participantIds: Seq[ParticipantId],
      approvedVettedPackages: Seq[String],
  )(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      dsoRules <- store.getDsoRules()
      vettedPackages <- participantIds.traverse { pId =>
        participantAdminConnection.listVettedPackages(pId, dsoRules.domain)
      }
    } yield {
      val vettedPackagesPackageIds =
        vettedPackages.flatMap(_.flatMap(_.item.packages.map(_.packageId)))
      approvedVettedPackages.diff(vettedPackagesPackageIds).isEmpty
    }
  }

  private def retrieveNextRoundToClaim(beneficiaries: Seq[BeneficiaryConfig])(implicit
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
      lastReceivedForOpt = svLastReceivedFor(rewardState.payload)
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
      beneficiaries,
    )
  }

  private def svLastReceivedFor(rewardState: SvRewardState): Option[Long] = {
    // -1 is the value set in DsoRules_ConfirmSvOnboarding for new SVs
    Option(rewardState.state.lastRoundCollected.number.longValue()).filter(_ > -1)
  }

  override protected def completeTask(task: ReceiveSvRewardCouponTrigger.Task)(implicit
      tc: TraceContext
  ): Future[TaskOutcome] = {
    val ReceiveSvRewardCouponTrigger.Task(
      dsoRules,
      svRewardWeight,
      rewardState,
      unclaimedRound,
      beneficiaries,
    ) =
      task
    val lastReceivedForOpt = svLastReceivedFor(rewardState.payload)
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
      SvUtil.weightDistributionForSv(svRewardWeight, beneficiaries, svParty)(logger, tc)
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
    val nextRound = retrieveNextRoundToClaim(task.beneficiaries)
    nextRound.forall(_ != task)
  }

}

object ReceiveSvRewardCouponTrigger {

  case class Task(
      dsoRules: AssignedContract[DsoRules.ContractId, DsoRules],
      svRewardWeight: Long,
      rewardState: AssignedContract[SvRewardState.ContractId, SvRewardState],
      round: OpenMiningRoundContract,
      beneficiaries: Seq[BeneficiaryConfig],
  ) extends PrettyPrinting {
    import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
    import com.digitalasset.canton.participant.pretty.Implicits.prettyContractId

    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("dsoRulesCid", _.dsoRules.contractId),
        param("svRewardWeight", _.svRewardWeight),
        param("rewardState", _.rewardState),
        param("round", _.round),
      )
  }

}
