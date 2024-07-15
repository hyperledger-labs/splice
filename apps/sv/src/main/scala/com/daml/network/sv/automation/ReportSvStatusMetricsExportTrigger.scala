// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.automation

import com.daml.metrics.api.MetricHandle.Gauge
import com.daml.metrics.api.{MetricName, MetricsContext}
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskNoop,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.splice.dso.svstate.SvStatusReport
import com.daml.network.environment.SpliceMetrics
import com.daml.network.sv.automation.ReportSvStatusMetricsExportTrigger.{
  SvCometBftMetrics,
  SvStatusMetrics,
}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.store.SvDsoStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import com.digitalasset.canton.util.ShowUtil.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*

/** A trigger to export the SvStatus reports as metrics. */
class ReportSvStatusMetricsExportTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
    cometBftNode: Option[CometBftNode],
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[SvStatusReport.ContractId, SvStatusReport](
      store,
      SvStatusReport.COMPANION,
    ) {

  private val svCometBftMetrics = new SvCometBftMetrics(context.metricsFactory)

  private val perSvStatusMetrics
      : TrieMap[ReportSvStatusMetricsExportTrigger.SvId, SvStatusMetrics] =
    TrieMap.empty

  private def getSvStatusMetrics(svId: ReportSvStatusMetricsExportTrigger.SvId): SvStatusMetrics =
    perSvStatusMetrics.getOrElse(
      svId,
      // We must synchronize here to avoid allocating the metrics for the same sv multiple times, which would lead to
      // duplicate metric labels being reported by OpenTelemetry.
      blocking {
        synchronized {
          perSvStatusMetrics.getOrElseUpdate(svId, SvStatusMetrics(svId, context.metricsFactory))
        }
      },
    )

  private def closeAllOffboardedSvMetrics(
      svIdsFromDsoRules: Set[ReportSvStatusMetricsExportTrigger.SvId]
  )(implicit tc: TraceContext): Unit = {

    val svIdsToClose = perSvStatusMetrics.keySet.toSet -- svIdsFromDsoRules
    perSvStatusMetrics.view.filterKeys(svIdsToClose.contains).foreach(_._2.close())
    blocking {
      synchronized {
        perSvStatusMetrics --= svIdsToClose: Unit
      }
    }
    if (svIdsToClose.nonEmpty)
      logger.info(s"closed SvStatusReport metrics of $svIdsToClose")
  }

  override protected def completeTask(
      task: AssignedContract[
        SvStatusReport.ContractId,
        SvStatusReport,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = for {
    dsoRules <- store.getDsoRules()
    svIdsFromDsoRules = dsoRules.payload.svs.asScala.map { case (svParty, svInfo) =>
      ReportSvStatusMetricsExportTrigger.SvId(svParty, svInfo.name)
    }.toSet
    // Note: We rely on there always being other SVs that still update their status reports. This is a reasonable assumption:
    // If no status reports go through the domain, we get alerts anyway so it doesn't matter much whether one SV is
    // removed or not.
    _ = closeAllOffboardedSvMetrics(svIdsFromDsoRules)
    report = task.payload
    svId = ReportSvStatusMetricsExportTrigger.SvId(svParty = report.sv, svName = report.svName)
    (earliestBlockHeight, latestBlockHeight) <- cometBftNode match {
      case None => Future.successful((0L, 0L))
      case Some(cometBftNode) =>
        for {
          earliestBlockHeight <- cometBftNode.getEarliestBlockHeight()
          latestBlockHeight <- cometBftNode.getLatestBlockHeight()
        } yield (earliestBlockHeight, latestBlockHeight)
    }
  } yield {
    svCometBftMetrics.cometBftEarliestBlockHeight.updateValue(earliestBlockHeight)
    svCometBftMetrics.cometBftLatestBlockHeight.updateValue(latestBlockHeight)
    report.status.toScala match {
      case None => TaskNoop
      case _ if !svIdsFromDsoRules.contains(svId) =>
        TaskSuccess(s"$svId is off-boarded. Not updating SvStatusReport metrics")
      case Some(status) =>
        val metrics = getSvStatusMetrics(svId)
        metrics.reportNumber.updateValue(task.payload.number)
        metrics.creationTime.updateValue(
          CantonTimestamp.assertFromInstant(task.payload.status.get().createdAt).toMicros
        )
        metrics.mediatorSynchronizerTime.updateValue(
          CantonTimestamp.assertFromInstant(status.mediatorSynchronizerTime).toMicros
        )
        metrics.participantSynchronizerTime.updateValue(
          CantonTimestamp.assertFromInstant(status.participantSynchronizerTime).toMicros
        )
        metrics.cometBftHeight.updateValue(status.cometBftHeight)
        metrics.latestOpenRound.updateValue(status.latestOpenRound.number)
        TaskSuccess(show"Updated metrics")
    }
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super
      .closeAsync()
      .appended(
        SyncCloseable(
          "per-sv status report metrics",
          perSvStatusMetrics.values.foreach(_.close()),
        )
      )
      .appended(
        SyncCloseable(
          "per-sv cometbft metrics", {
            svCometBftMetrics.close()
          },
        )
      )
}

object ReportSvStatusMetricsExportTrigger {

  private case class SvId(svParty: String, svName: String)

  private case class SvCometBftMetrics(
      metricsFactory: CantonLabeledMetricsFactory
  ) extends AutoCloseable {

    private implicit val mc: MetricsContext =
      MetricsContext.Empty

    private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "sv_cometbft"

    private def gauge(name: String, initial: Long)(implicit mc: MetricsContext): Gauge[Long] =
      metricsFactory.gauge(prefix :+ name, initial = initial)

    val cometBftEarliestBlockHeight: Gauge[Long] = gauge("earliest_block_height", initial = 0L)
    val cometBftLatestBlockHeight: Gauge[Long] = gauge("latest_block_height", initial = 0L)

    override def close(): Unit = {
      cometBftEarliestBlockHeight.close()
      cometBftLatestBlockHeight.close()
    }
  }

  private case class SvStatusMetrics(
      svId: SvId,
      metricsFactory: CantonLabeledMetricsFactory,
  ) extends AutoCloseable {

    private implicit val mc: MetricsContext =
      MetricsContext(
        Map("report_publisher" -> svId.svName, "report_publisher_party" -> svId.svParty)
      )
    private val prefix: MetricName = SpliceMetrics.MetricsPrefix :+ "sv_status_report"
    private def gauge(name: String, initial: Long)(implicit mc: MetricsContext): Gauge[Long] =
      metricsFactory.gauge(prefix :+ name, initial = initial)

    private val minTimestampValue = CantonTimestamp.MinValue.toMicros

    val reportNumber: Gauge[Long] = gauge("number", initial = 0L)
    val creationTime: Gauge[Long] = gauge("creation_time_us", initial = minTimestampValue)
    val cometBftHeight: Gauge[Long] = gauge("cometbft_height", initial = -1L)
    val mediatorSynchronizerTime: Gauge[Long] =
      gauge("domain_time_us", initial = minTimestampValue)(
        mc.withExtraLabels("target_node" -> "mediator")
      )
    val participantSynchronizerTime: Gauge[Long] =
      gauge("domain_time_us", initial = minTimestampValue)(
        mc.withExtraLabels("target_node" -> "participant")
      )
    val latestOpenRound: Gauge[Long] = gauge("latest_open_round", initial = -1L)

    override def close(): Unit = {
      reportNumber.close()
      creationTime.close()
      cometBftHeight.close()
      mediatorSynchronizerTime.close()
      participantSynchronizerTime.close()
      latestOpenRound.close()
    }
  }

}
