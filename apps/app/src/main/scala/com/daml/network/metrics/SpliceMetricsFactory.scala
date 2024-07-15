// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.metrics

import com.daml.metrics.api.MetricsContext
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.splitwell.metrics.SplitwellAppMetrics
import com.daml.network.sv.metrics.SvAppMetrics
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.digitalasset.canton.buildinfo.BuildInfo
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory.CantonOpenTelemetryMetricsFactory
import com.digitalasset.canton.metrics.MetricsFactoryType
import io.opentelemetry.api.metrics.{Meter, MeterProvider}

import scala.collection.concurrent.TrieMap

case class SpliceMetricsFactory(
    meter: Meter,
    factoryType: MetricsFactoryType,
) {

  private val validators = TrieMap[String, ValidatorAppMetrics]()
  private val svs = TrieMap[String, SvAppMetrics]()
  private val scans = TrieMap[String, ScanAppMetrics]()
  private val splitwells = TrieMap[String, SplitwellAppMetrics]()

  def forValidator(name: String): ValidatorAppMetrics = {
    validators.getOrElseUpdate(
      name, {
        val metricsContext = MetricsContext("node_name" -> name, "node_type" -> "validator")
        new ValidatorAppMetrics(
          createLabeledMetricsFactory(metricsContext)
        )
      },
    )
  }

  def forSv(name: String): SvAppMetrics = {
    svs.getOrElseUpdate(
      name, {
        val metricsContext = MetricsContext("node_name" -> name, "node_type" -> "sv")
        new SvAppMetrics(
          createLabeledMetricsFactory(metricsContext)
        )
      },
    )
  }

  def forScan(name: String): ScanAppMetrics = {
    scans.getOrElseUpdate(
      name, {
        val metricsContext = MetricsContext("node_name" -> name, "node_type" -> "scan")
        new ScanAppMetrics(
          createLabeledMetricsFactory(metricsContext)
        )
      },
    )
  }

  def forSplitwell(name: String): SplitwellAppMetrics = {
    splitwells.getOrElseUpdate(
      name, {
        val metricsContext = MetricsContext("node_name" -> name, "node_type" -> "splitwell")
        new SplitwellAppMetrics(
          createLabeledMetricsFactory(metricsContext)
        )
      },
    )
  }

  def createLabeledMetricsFactory(extraContext: MetricsContext) = {
    factoryType match {
      case MetricsFactoryType.InMemory(provider) =>
        provider(extraContext)
      case MetricsFactoryType.External =>
        new CantonOpenTelemetryMetricsFactory(
          meter,
          globalMetricsContext = MetricsContext(
            "canton_version" -> BuildInfo.version
          ).merge(extraContext),
        )
    }
  }

}

object SpliceMetricsFactory {
  def forConfig(
      meter: MeterProvider,
      metricsFactoryType: MetricsFactoryType,
  ): SpliceMetricsFactory = {
    new SpliceMetricsFactory(
      meter.meterBuilder("cn").build(),
      metricsFactoryType,
    )
  }
}
