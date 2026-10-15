// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter}
import com.daml.metrics.api.MetricQualification.Errors
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletconfig.RewardVersion
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_MiningRound_StartIssuing
import org.lfdecentralizedtrust.splice.codegen.java.splice.issuance.OpenMiningRoundSummary
import org.lfdecentralizedtrust.splice.codegen.java.splice.round.SummarizingMiningRound
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_MiningRound_StartIssuing
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerConnection, SpliceMetrics}
import org.lfdecentralizedtrust.splice.http.v0.definitions
import org.lfdecentralizedtrust.splice.http.v0.definitions.GetRewardAccountingActivityTotalsResponse.members.{
  RewardAccountingActivityTotalsCannotProvide,
  RewardAccountingActivityTotalsOk,
  RewardAccountingActivityTotalsUndetermined,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{BftScanConnection, ScanConnection}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.{AppRewardCouponsSum, SvDsoStore}
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.OptionConverters.*

/** This is a polling trigger to avoid issues where SVs run out of retries (e.g. due to the synchronizer being down)
  * and then the round gets stuck forever in the summarizing state.
  */
class SummarizingMiningRoundTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    scanConnectionF: () => Future[ScanConnection],
    bftScanConnectionF: () => Future[BftScanConnection],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[SummarizingMiningRoundTrigger.Task] {

  import SummarizingMiningRoundTrigger.*

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty

  private val miningRoundMetrics = new SummarizingMiningRoundMetrics(context.metricsFactory)

  private def amuletRulesStartIssuingAction(
      miningRoundCid: SummarizingMiningRound.ContractId,
      summary: OpenMiningRoundSummary,
  ): ActionRequiringConfirmation =
    new ARC_AmuletRules(
      new CRARC_MiningRound_StartIssuing(
        new AmuletRules_MiningRound_StartIssuing(
          miningRoundCid,
          summary,
        )
      )
    )

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = for {
    summarizingRounds <- store.listOldestSummarizingMiningRounds()
    confirmedCids <- listConfirmedMiningRoundStartIssuingCids()
  } yield summarizingRounds
    .filterNot(round => confirmedCids.contains(round.contractId))
    .map(Task(_))

  override def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.summarizingRound.contract.payload.round.number
    (for {
      rewards <- queryRewards(
        task.summarizingRound.payload,
        task.summarizingRound.domain,
      )
      dsoRules <- store.getDsoRules()
      action = amuletRulesStartIssuingAction(
        task.summarizingRound.contractId,
        rewards.summary,
      )
      queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_ConfirmAction(
          svParty.toProtoPrimitive,
          action,
        )
      )
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(
            TaskSuccess(
              s"skipping as confirmation from ${svParty} is already created for summarizing round ${round}"
            )
          )
        case QueryResult(offset, None) =>
          connection
            .submit(
              actAs = Seq(svParty),
              readAs = Seq(dsoParty),
              update = cmd,
            )
            .withDedup(
              commandId = SpliceLedgerConnection.CommandId(
                "org.lfdecentralizedtrust.splice.sv.createMiningRoundStartIssuingConfirmation",
                Seq(svParty, dsoParty),
                task.summarizingRound.contractId.contractId,
              ),
              deduplicationOffset = offset,
            )
            .yieldUnit()
            .map { _ =>
              TaskSuccess(
                s"created confirmation for summarizing mining round ${round}"
              )
            }
      }
    } yield taskOutcome).recover { case _: ScanTotalsNotComputed => TaskNoop }
  }

  override def isStaleTask(task: SummarizingMiningRoundTrigger.Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    // We don't bother checking if a confirmation exists since this is handled in completeTask
    store.multiDomainAcsStore
      .lookupContractById(SummarizingMiningRound.COMPANION)(task.summarizingRound.contractId)
      .map(_.isEmpty)

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(
      payload: splice.round.SummarizingMiningRound,
      domain: SynchronizerId,
  )(implicit
      ec: ExecutionContext,
      traceContext: TraceContext,
  ): Future[RoundRewards] = {
    val round = payload.round.number
    val issuanceConfig = payload.issuanceConfig
    val faucetCapIsZero = issuanceConfig.optValidatorFaucetCap.toScala
      .exists(_.compareTo(java.math.BigDecimal.ZERO) <= 0)
    for {
      appRewardCoupons <-
        if (useTrafficBasedAppRewards(payload)) {
          fetchRewardAccountingTotals(round).map { totals =>
            // The total featured app rewards (in CC) is the sum of the minting
            // allowance and the thresholded amount reported by Scan.
            val appRewardsInCc =
              (BigDecimal(totals.totalAppRewardMintingAllowance) +
                BigDecimal(totals.totalAppRewardThresholded))
                .setScale(10, BigDecimal.RoundingMode.HALF_EVEN)
            AppRewardCouponsSum(featured = appRewardsInCc, unfeatured = BigDecimal(0))
          }
        } else {
          store.sumAppRewardCouponsOnDomain(round, domain)
        }
      validatorRewardCoupons <- store.sumValidatorRewardCouponsOnDomain(
        round,
        domain,
      )
      validatorFaucetCoupons <-
        if (faucetCapIsZero) Future.successful(0L)
        else store.countValidatorFaucetCouponsOnDomain(round, domain)
      validatorLivenessActivityRecords <-
        if (faucetCapIsZero) Future.successful(0L)
        else store.countValidatorLivenessActivityRecordsOnDomain(round, domain)
      svRewardCouponsWeightSum <- store.sumSvRewardCouponWeightsOnDomain(
        round,
        domain,
      )
    } yield {
      RoundRewards(
        round = round,
        featuredAppRewardCoupons = appRewardCoupons.featured,
        unfeaturedAppRewardCoupons = appRewardCoupons.unfeatured,
        validatorRewardCoupons = validatorRewardCoupons,
        validatorLivenessActivityRecords =
          validatorFaucetCoupons + validatorLivenessActivityRecords,
        svRewardCouponsWeightSum = svRewardCouponsWeightSum,
      )
    }
  }

  private def listConfirmedMiningRoundStartIssuingCids()(implicit
      tc: TraceContext
  ): Future[Set[SummarizingMiningRound.ContractId]] =
    store.listConfirmationsByConfirmer(svParty).map { confirmations =>
      confirmations.iterator.flatMap { c =>
        c.payload.action match {
          case arc: ARC_AmuletRules =>
            arc.amuletRulesAction match {
              case crarc: CRARC_MiningRound_StartIssuing =>
                Some(crarc.amuletRules_MiningRound_StartIssuingValue.miningRoundCid)
              case _ => None
            }
          case _ => None
        }
      }.toSet
    }

  private def useTrafficBasedAppRewards(
      payload: splice.round.SummarizingMiningRound
  ): Boolean =
    payload.rewardConfig.toScala.exists(
      _.mintingVersion == RewardVersion.REWARDVERSION_TRAFFICBASEDAPPREWARDS
    )

  private def fetchRewardAccountingTotals(
      round: Long
  )(implicit tc: TraceContext): Future[definitions.RewardAccountingActivityTotalsOk] = {

    def bftReadTotals
        : Future[Either[ScanTotalsNotComputed, definitions.RewardAccountingActivityTotalsOk]] = {
      miningRoundMetrics.summarizingRoundTotalsBftReads.mark()
      for {
        bftScan <- bftScanConnectionF()
        response <- bftScan.getRewardAccountingActivityTotalsWithScanUris(round)
      } yield response match {
        case (RewardAccountingActivityTotalsOk(ok), scanUris) =>
          logger.info(
            s"Obtained the reward accounting totals for round $round via BFT read from scans: ${scanUris
                .mkString(", ")}."
          )
          Right(ok)
        case _ => Left(BftScanTotalsNotYetComputed)
      }
    }

    for {
      ownScan <- scanConnectionF()
      response <- ownScan.getRewardAccountingActivityTotals(round)
      totals <- response match {
        case RewardAccountingActivityTotalsOk(ok) =>
          Future.successful(ok)
        case RewardAccountingActivityTotalsUndetermined(_) =>
          Future.failed(OwnScanTotalsNotYetComputed)
        case RewardAccountingActivityTotalsCannotProvide(_) =>
          bftReadTotals.transform(_.flatMap(_.toTry))
      }
    } yield totals
  }
}

object SummarizingMiningRoundTrigger {
  sealed trait ScanTotalsNotComputed extends RuntimeException
  private[confirmation] case object OwnScanTotalsNotYetComputed extends ScanTotalsNotComputed
  private[confirmation] case object BftScanTotalsNotYetComputed extends ScanTotalsNotComputed

  final case class RoundRewards(
      round: Long,
      featuredAppRewardCoupons: BigDecimal,
      unfeaturedAppRewardCoupons: BigDecimal,
      validatorRewardCoupons: BigDecimal,
      validatorLivenessActivityRecords: Long,
      svRewardCouponsWeightSum: Long,
  ) extends PrettyPrinting {
    lazy val summary: splice.issuance.OpenMiningRoundSummary =
      new splice.issuance.OpenMiningRoundSummary(
        validatorRewardCoupons.bigDecimal,
        featuredAppRewardCoupons.bigDecimal,
        unfeaturedAppRewardCoupons.bigDecimal,
        svRewardCouponsWeightSum,
        Optional.of(validatorLivenessActivityRecords),
      )

    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("featuredAppRewardCoupons", _.featuredAppRewardCoupons),
        param("unfeaturedAppRewardCoupons", _.unfeaturedAppRewardCoupons),
        param("validatorRewardCoupons", _.validatorRewardCoupons),
        param("validatorLivenessActivityRecords", _.validatorLivenessActivityRecords),
        param("svRewardCouponsWeightSum", _.svRewardCouponsWeightSum),
      )
  }

  final case class Task(
      summarizingRound: AssignedContract[
        splice.round.SummarizingMiningRound.ContractId,
        splice.round.SummarizingMiningRound,
      ]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("summarizingRound", _.summarizingRound)
      )
  }

  class SummarizingMiningRoundMetrics(metricsFactory: LabeledMetricsFactory) {

    private val metricsContext = MetricsContext.Empty

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix
    val summarizingRoundTotalsBftReads: Meter =
      metricsFactory.meter(
        MetricInfo(
          name = prefix :+ "summarizing_mining_round" :+ "totals_bft_reads",
          summary = "Count of BFT reads of the reward-accounting totals",
          description =
            "This metric counts the BFT reads of the reward-accounting totals performed by the SummarizingMiningRound trigger, i.e., the cases where this SV's own Scan could not provide the totals and it had to be obtained via a BFT read against peer Scans.",
          qualification = Errors,
        )
      )(metricsContext)
  }
}
