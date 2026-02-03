// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation

import com.daml.metrics.api.MetricHandle.{Gauge, LabeledMetricsFactory}
import com.daml.metrics.api.{MetricInfo, MetricsContext}
import com.daml.metrics.api.MetricQualification.Debug
import org.lfdecentralizedtrust.splice.automation.{
  OnAssignedContractTrigger,
  TaskOutcome,
  TaskSuccess,
  TriggerContext,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.validatorlicense.ValidatorLicense
import org.lfdecentralizedtrust.splice.environment.SpliceMetrics
import org.lfdecentralizedtrust.splice.sv.automation.ReportValidatorLicenseMetricsExportTrigger.ValidatorLicenseMetrics
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.util.{AssignedContract, Codec}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.lifecycle.{AsyncOrSyncCloseable, SyncCloseable}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import com.digitalasset.canton.util.ShowUtil.*

import scala.collection.concurrent.TrieMap
import scala.concurrent.{blocking, ExecutionContext, Future}
import scala.jdk.OptionConverters.*

/** A trigger to export the ValidatorLicense reports as metrics. */
class ReportValidatorLicenseMetricsExportTrigger(
    override protected val context: TriggerContext,
    store: SvDsoStore,
)(implicit
    override val ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnAssignedContractTrigger.Template[ValidatorLicense.ContractId, ValidatorLicense](
      store,
      ValidatorLicense.COMPANION,
    ) {

  private val perValidatorMetrics: TrieMap[PartyId, ValidatorLicenseMetrics] =
    TrieMap.empty

  private def getValidatorLicenseMetrics(
      validator: PartyId,
      version: String,
      contactPoint: String,
  ): ValidatorLicenseMetrics =
    // We must synchronize here to avoid allocating the metrics for the same valdiator multiple times, which would lead to
    // duplicate metric labels being reported by OpenTelemetry.
    blocking {
      mutex.exclusive {
        val metric = perValidatorMetrics.updateWith(validator) {
          case None =>
            Some(ValidatorLicenseMetrics(validator, version, contactPoint, context.metricsFactory))
          case Some(metric) if metric.version != version || metric.contactPoint != contactPoint =>
            // We can't update labels on a gauge so we close the metric and create a new one.
            metric.close()
            Some(ValidatorLicenseMetrics(validator, version, contactPoint, context.metricsFactory))
          case Some(metric) => Some(metric)
        }
        metric.getOrElse(
          throw Status.INTERNAL
            .withDescription(s"Internal error: Validator metric for $validator did not get created")
            .asRuntimeException()
        )
      }
    }

  override protected def completeTask(
      task: AssignedContract[
        ValidatorLicense.ContractId,
        ValidatorLicense,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val validator = Codec.tryDecode(Codec.Party)(task.payload.validator)
    val lastActiveAt = CantonTimestamp
      .assertFromInstant(task.payload.lastActiveAt.toScala.getOrElse(task.contract.createdAt))
      .toMicros
    val metadata = task.payload.metadata.toScala
    val version = metadata.fold("")(_.version)
    val contactPoint = metadata.fold("")(_.contactPoint)
    getValidatorLicenseMetrics(validator, version, contactPoint).lastActiveAt.updateValue(
      lastActiveAt
    )
    Future.successful(
      TaskSuccess(show"Updated metrics for $validator")
    )
  }

  override protected def closeAsync(): Seq[AsyncOrSyncCloseable] =
    super
      .closeAsync()
      .appended(
        SyncCloseable(
          "per-validator metrics",
          perValidatorMetrics.values.foreach(_.close()),
        )
      )
}

object ReportValidatorLicenseMetricsExportTrigger {

  private case class ValidatorLicenseMetrics(
      validator: PartyId,
      version: String,
      contactPoint: String,
      metricsFactory: LabeledMetricsFactory,
  ) extends AutoCloseable {

    private implicit val mc: MetricsContext =
      MetricsContext(
        Map(
          "contact_point" -> contactPoint,
          "version" -> version,
          "validator_party" -> validator.toString,
        )
      )

    private val minTimestampValue = CantonTimestamp.MinValue.toMicros

    val lastActiveAt: Gauge[Long] = metricsFactory.gauge(
      MetricInfo(
        SpliceMetrics.MetricsPrefix :+ "validator_last_active_at_us",
        "The last time the validator was active",
        Debug,
      ),
      minTimestampValue,
    )

    override def close(): Unit = {
      lastActiveAt.close()
    }
  }

}
