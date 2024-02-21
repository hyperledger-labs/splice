package com.daml.network.sv.automation

import com.daml.metrics.api.MetricHandle.Gauge
import com.daml.metrics.api.{MetricName, MetricsContext}
import com.daml.network.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import com.daml.network.codegen.java.cn.svc.svstatus.SvStatusReport
import com.daml.network.environment.CNMetrics
import com.daml.network.sv.automation.SvStatusReportMetricsExportTrigger.SvStatusMetrics
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.AssignedContract
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.jdk.OptionConverters.*

/** A trigger to export the SvStatus reports as metrics. */
class SvStatusReportMetricsExportTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[SvStatusReport.ContractId, SvStatusReport](
      store,
      SvStatusReport.COMPANION,
    ) {

  private val perSvMetrics: TrieMap[String, SvStatusMetrics] = TrieMap.empty

  private def getSvMetrics(sv: String): SvStatusMetrics =
    perSvMetrics.get(sv).getOrElse {
      // We must synchronize here to avoid allocating the metrics for the same sv multiple times, which would lead to
      // duplicate metric labels being reported by OpenTelemetry.
      blocking {
        synchronized {
          perSvMetrics.getOrElseUpdate(sv, SvStatusMetrics(sv, context.metricsFactory))
        }
      }
    }

  override protected def completeTask(
      task: AssignedContract[
        SvStatusReport.ContractId,
        SvStatusReport,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    Future {
      val report = task.payload
      report.status.toScala.foreach(status => {
        // We only update the metrics if the status is present to avoid the gauges starting at a non-sensical value.
        val metrics = getSvMetrics(task.payload.sv)
        metrics.reportNumber.updateValue(report.number)
        metrics.creationTime.updateValue(
          CantonTimestamp.assertFromInstant(report.status.get().createdAt).toMicros
        )
        metrics.mediatorDomainTime.updateValue(
          CantonTimestamp.assertFromInstant(status.mediatorDomainTime).toMicros
        )
        metrics.participantDomainTime.updateValue(
          CantonTimestamp.assertFromInstant(status.participantDomainTime).toMicros
        )
        metrics.cometBftHeight.updateValue(status.cometBftHeight)
        metrics.latestOpenRound.updateValue(status.latestOpenRound.number)
      })
      TaskSuccess(s"Updated SvStatusReport metrics")
    }
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super
      .closeAsync()
      .appended(
        SyncCloseable(
          "per-sv status report metrics",
          perSvMetrics.values.foreach(_.close()),
        )
      )
}

object SvStatusReportMetricsExportTrigger {
  private case class SvStatusMetrics(
      svPartyId: String,
      metricsFactory: CantonLabeledMetricsFactory,
  ) extends AutoCloseable {
    private implicit val mc: MetricsContext = MetricsContext(
      "report_publisher" -> svPartyId
    )
    private val prefix: MetricName = CNMetrics.MetricsPrefix :+ "sv_status_report"
    private def gauge(name: String, initial: Long): Gauge[Long] =
      metricsFactory.gauge(prefix :+ name, initial = initial)

    private val minTimestampValue = CantonTimestamp.MinValue.toMicros

    val reportNumber: Gauge[Long] = gauge("number", initial = 0L)
    val creationTime: Gauge[Long] = gauge("creation_time_us", initial = minTimestampValue)
    val cometBftHeight: Gauge[Long] = gauge("cometbft_height", initial = -1L)
    val mediatorDomainTime: Gauge[Long] =
      gauge("mediator_domain_time_us", initial = minTimestampValue)
    val participantDomainTime: Gauge[Long] =
      gauge("participant_domain_time_us", initial = minTimestampValue)
    val latestOpenRound: Gauge[Long] = gauge("latest_open_round", initial = -1L)

    override def close(): Unit = {
      reportNumber.close()
      creationTime.close()
      cometBftHeight.close()
      mediatorDomainTime.close()
      participantDomainTime.close()
      latestOpenRound.close()
    }
  }

}
