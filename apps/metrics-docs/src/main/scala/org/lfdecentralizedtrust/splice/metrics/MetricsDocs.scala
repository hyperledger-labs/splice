// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.metrics

import better.files.File
import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.{MetricDoc, MetricsDocGenerator}
import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.admin.api.client.DamlGrpcClientMetrics
import org.lfdecentralizedtrust.splice.automation.TriggerMetrics
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanStoreMetrics
import org.lfdecentralizedtrust.splice.scan.metrics.ScanMediatorVerdictIngestionMetrics
import org.lfdecentralizedtrust.splice.sv.automation.singlesv.SequencerPruningMetrics
import org.lfdecentralizedtrust.splice.sv.automation.ReportSvStatusMetricsExportTrigger
import org.lfdecentralizedtrust.splice.sv.store.db.DbSvDsoStoreMetrics
import org.lfdecentralizedtrust.splice.store.{DomainParamsStore, HistoryMetrics, StoreMetrics}
import org.lfdecentralizedtrust.splice.wallet.metrics.AmuletMetrics

final case class GeneratedMetrics(
    common: List[MetricDoc.Item],
    validator: List[MetricDoc.Item],
    sv: List[MetricDoc.Item],
    scan: List[MetricDoc.Item],
) {
  def render(): String =
    Seq(
      s"""|..
          |   Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
          |..
          |   SPDX-License-Identifier: Apache-2.0
          |
          |.. _metrics-reference:
          |
          |Metrics Reference
          |=================
          |""".stripMargin,
      renderSection("Common", common),
      renderSection("Validator", validator),
      renderSection("SV", sv),
      renderSection("Scan", scan),
    ).mkString("\n")

  def renderSection(prefix: String, metrics: List[MetricDoc.Item]): String = {
    val header = s"$prefix Metrics"
    (Seq(
      header,
      "+" * header.length,
    ) ++
      // We seem to automatically pull in the daml.cache metrics which make no sense for splice at this point
      metrics.filter(m => !m.name.startsWith("daml.cache")).map(renderMetric(_))).mkString("\n")
  }

  def renderMetric(metric: MetricDoc.Item): String =
    Seq(
      metric.name,
      "^" * metric.name.length,
      s"* **Summary**: ${metric.summary}",
      s"* **Description**: ${metric.description}",
      s"* **Type**: ${metric.metricType}",
      s"* **Qualification**: ${metric.qualification}",
      "\n",
    ).mkString("\n")
}

object MetricsDocs {
  private def metricsDocs(): GeneratedMetrics = {
    val walletUserParty = PartyId.tryFromProtoPrimitive("wallet_user::namespace")
    val svParty = PartyId.tryFromProtoPrimitive("sv::namespace")
    val generator = new MetricsDocGenerator()
    // common
    new DomainParamsStore.Metrics(generator)
    new HistoryMetrics(generator)(MetricsContext.Empty)
    new StoreMetrics(generator)(MetricsContext.Empty)
    new DamlGrpcClientMetrics(generator, "")
    new TriggerMetrics(generator)
    val commonMetrics = generator.getAll()
    generator.reset()
    // validator
    new AmuletMetrics(walletUserParty, generator)
    val validatorMetrics = generator.getAll()
    generator.reset()
    // sv
    new DbSvDsoStoreMetrics(generator)
    new SequencerPruningMetrics(generator)
    new ReportSvStatusMetricsExportTrigger.SvCometBftMetrics(generator)
    new ReportSvStatusMetricsExportTrigger.SvStatusMetrics(
      ReportSvStatusMetricsExportTrigger.SvId(svParty.toProtoPrimitive, "svName"),
      generator,
    )
    val svMetrics = generator.getAll()
    generator.reset()
    // scan
    new DbScanStoreMetrics(
      generator,
      NamedLoggerFactory.root,
      ProcessingTimeout(),
    )
    new ScanMediatorVerdictIngestionMetrics(generator)
    val scanMetrics = generator.getAll()
    generator.reset()
    GeneratedMetrics(
      commonMetrics,
      validatorMetrics,
      svMetrics,
      scanMetrics,
    )
  }

  def main(args: Array[String]): Unit = {
    val file = File(args(0))
    file.parent.createDirectoryIfNotExists()
    val docs = metricsDocs()
    file.overwrite(docs.render()).discard[File]
  }
}
