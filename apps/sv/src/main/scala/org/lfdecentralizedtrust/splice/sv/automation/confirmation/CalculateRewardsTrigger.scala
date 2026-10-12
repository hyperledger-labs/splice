// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.confirmation

import com.daml.metrics.api.MetricHandle.{LabeledMetricsFactory, Meter, Timer}
import com.daml.metrics.api.MetricQualification.{Errors, Latency}
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.automation.{
  PollingParallelTaskExecutionTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.cryptohash.Hash
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.rewardaccountingv2.CalculateRewardsV2
import org.lfdecentralizedtrust.splice.http.v0.definitions.GetRewardAccountingRootHashResponse.members.{
  RewardAccountingRootHashCannotProvide,
  RewardAccountingRootHashOk,
  RewardAccountingRootHashUndetermined,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.ActionRequiringConfirmation
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.ARC_AmuletRules
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.amuletrules_actionrequiringconfirmation.CRARC_StartProcessingRewardsV2
import org.lfdecentralizedtrust.splice.environment.{SpliceLedgerConnection, SpliceMetrics}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.{BftScanConnection, ScanConnection}
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.QueryResult
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.AssignedContract
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.daml.metrics.api.{MetricInfo, MetricName, MetricsContext}
import com.daml.metrics.api.MetricsContext.Implicits.empty
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

abstract class CalculateRewardsTriggerBase(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    getOwnScanConnection: () => Future[ScanConnection],
    getPeerBftScanConnection: () => Future[BftScanConnection],
    isDryRun: Boolean,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends PollingParallelTaskExecutionTrigger[CalculateRewardsTriggerBase.Task] {

  import CalculateRewardsTriggerBase.*

  private val svParty = store.key.svParty
  private val dsoParty = store.key.dsoParty
  private val rewardMetrics = new CalculateRewardsMetrics(context.metricsFactory, isDryRun)

  override def extraMetricLabels: Seq[(String, String)] = Seq("dryRun" -> isDryRun.toString)

  override def retrieveTasks()(implicit tc: TraceContext): Future[Seq[Task]] = for {
    // These are ordered by round, so we process the oldest first
    calculateRewards <- store.listCalculateRewardsV2()
    calculateRewardsForThisTrigger = calculateRewards.filter(_.payload.dryRun == isDryRun)
    confirmedCids <- listConfirmedCalculateRewardsCids()
  } yield calculateRewardsForThisTrigger
    .filterNot(c => confirmedCids.contains(c.contractId))
    .map(Task(_))

  override def completeTask(
      task: Task
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val round = task.calculateRewards.payload.round.number
    for {
      rootHash <- getRootHash(round)
      action = startProcessingRewardsAction(
        task.calculateRewards.contractId,
        rootHash,
      )
      queryResult <- store.lookupConfirmationByActionWithOffset(svParty, action)
      taskOutcome <- queryResult match {
        case QueryResult(_, Some(_)) =>
          Future.successful(TaskNoop)
        case QueryResult(offset, None) =>
          for {
            dsoRules <- store.getDsoRules()
            cmd = dsoRules.exercise(
              _.exerciseDsoRules_ConfirmAction(
                svParty.toProtoPrimitive,
                action,
              )
            )
            _ <- connection
              .submit(
                actAs = Seq(svParty),
                readAs = Seq(dsoParty),
                update = cmd,
              )
              .withDedup(
                commandId = SpliceLedgerConnection.CommandId(
                  "org.lfdecentralizedtrust.splice.sv.createStartProcessingRewardsV2Confirmation",
                  Seq(svParty, dsoParty),
                  task.calculateRewards.contractId.contractId,
                ),
                deduplicationOffset = offset,
              )
              .yieldUnit()
            delay = java.time.Duration.between(
              task.calculateRewards.payload.roundClosedAt,
              context.clock.now.toInstant,
            )
            _ = rewardMetrics.calculateRewardsProcessingDelay.update(delay)
          } yield TaskSuccess(
            s"created confirmation for CalculateRewardsV2 round $round, processingDelay=$delay"
          )
      }
    } yield taskOutcome
  }

  override def isStaleTask(task: Task)(implicit
      tc: TraceContext
  ): Future[Boolean] =
    store.multiDomainAcsStore
      .lookupContractById(CalculateRewardsV2.COMPANION)(task.calculateRewards.contractId)
      .flatMap {
        case None => Future.successful(true)
        case Some(_) =>
          listConfirmedCalculateRewardsCids().map(
            _.contains(task.calculateRewards.contractId)
          )
      }

  private def listConfirmedCalculateRewardsCids()(implicit
      tc: TraceContext
  ): Future[Set[CalculateRewardsV2.ContractId]] =
    store.listConfirmationsByConfirmer(svParty).map { confirmations =>
      confirmations.iterator.flatMap { c =>
        c.payload.action match {
          case arc: ARC_AmuletRules =>
            arc.amuletRulesAction match {
              case crarc: CRARC_StartProcessingRewardsV2 =>
                Some(crarc.amuletRules_StartProcessingRewardsV2Value.calculateRewardsCid)
              case _ => None
            }
          case _ => None
        }
      }.toSet
    }

  private def getRootHash(round: Long)(implicit tc: TraceContext): Future[Hash] = {
    def rootHashUnavailable(reason: String): Nothing =
      throw Status.FAILED_PRECONDITION
        .withDescription(s"For round $round: $reason")
        .asRuntimeException()

    def bftReadRootHash: Future[Hash] = {
      rewardMetrics.calculateRewardsRootHashBftReads.mark()
      for {
        bftScan <- getPeerBftScanConnection()
        response <- bftScan.getRewardAccountingRootHashWithScanUris(round)
      } yield response match {
        case (RewardAccountingRootHashOk(ok), scanUris) =>
          logger.info(
            s"Obtained the root-hash for round $round via BFT read from scans: ${scanUris.mkString(", ")}."
          )
          new Hash(ok.rootHash)
        case _ => rootHashUnavailable("could not obtain root-hash via BFT read.")
      }
    }

    for {
      ownScan <- getOwnScanConnection()
      response <- ownScan.getRewardAccountingRootHash(round)
      rootHash <- response match {
        case RewardAccountingRootHashOk(ok) => Future.successful(new Hash(ok.rootHash))
        case RewardAccountingRootHashUndetermined(_) =>
          rootHashUnavailable("our own Scan has not yet computed the root hash.")
        case RewardAccountingRootHashCannotProvide(_) => bftReadRootHash
      }
    } yield rootHash
  }

  private def startProcessingRewardsAction(
      calculateRewardsCid: CalculateRewardsV2.ContractId,
      rootHash: Hash,
  ): ActionRequiringConfirmation =
    new ARC_AmuletRules(
      new CRARC_StartProcessingRewardsV2(
        new AmuletRules_StartProcessingRewardsV2(
          calculateRewardsCid,
          rootHash,
        )
      )
    )

}

class CalculateRewardsTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    getOwnScanConnection: () => Future[ScanConnection],
    getPeerBftScanConnection: () => Future[BftScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CalculateRewardsTriggerBase(
      context,
      store,
      connection,
      getOwnScanConnection,
      getPeerBftScanConnection,
      isDryRun = false,
    )

class CalculateRewardsDryRunTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    connection: SpliceLedgerConnection,
    getOwnScanConnection: () => Future[ScanConnection],
    getPeerBftScanConnection: () => Future[BftScanConnection],
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CalculateRewardsTriggerBase(
      context,
      store,
      connection,
      getOwnScanConnection,
      getPeerBftScanConnection,
      isDryRun = true,
    )

object CalculateRewardsTriggerBase {

  final case class Task(
      calculateRewards: AssignedContract[
        CalculateRewardsV2.ContractId,
        CalculateRewardsV2,
      ]
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("round", _.calculateRewards.payload.round.number),
        param("dryRun", _.calculateRewards.payload.dryRun.toString.unquoted),
      )
  }

  class CalculateRewardsMetrics(metricsFactory: LabeledMetricsFactory, dryRun: Boolean) {

    private val metricsContext = MetricsContext.Empty.withExtraLabels("dryRun" -> dryRun.toString)

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix

    val calculateRewardsProcessingDelay: Timer =
      metricsFactory.timer(
        MetricInfo(
          name = prefix :+ "calculate_rewards_v2" :+ "processing_delay",
          summary = "Delay between round close and CalculateRewardsV2 confirmation creation",
          description =
            "This metric captures the time it took between the closing of a round, and this SV's confirmation for the CalculateRewardsV2 contract's processing. Labeled with dryRun.",
          qualification = Latency,
        )
      )(metricsContext)

    val calculateRewardsRootHashBftReads: Meter =
      metricsFactory.meter(
        MetricInfo(
          name = prefix :+ "calculate_rewards_v2" :+ "root_hash_bft_reads",
          summary = "Count of BFT reads of the reward-accounting root-hash",
          description =
            "This metric counts the BFT reads of the reward-accounting root-hash performed by the CalculateRewardsV2 trigger, i.e., the cases where this SV's own Scan could not provide the root-hash and it had to be obtained via a BFT read against peer Scans. Labeled with dryRun.",
          qualification = Errors,
        )
      )(metricsContext)
  }
}
