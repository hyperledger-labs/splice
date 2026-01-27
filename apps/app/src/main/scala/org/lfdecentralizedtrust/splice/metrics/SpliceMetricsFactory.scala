// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.metrics

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.{DbStorageHistograms, MetricsFactoryProvider}
import org.lfdecentralizedtrust.splice.scan.metrics.ScanAppMetrics
import org.lfdecentralizedtrust.splice.splitwell.metrics.SplitwellAppMetrics
import org.lfdecentralizedtrust.splice.sv.metrics.SvAppMetrics
import org.lfdecentralizedtrust.splice.validator.metrics.ValidatorAppMetrics

import scala.collection.concurrent.TrieMap

case class SpliceMetricsFactory(
    metricsFactoryProvider: MetricsFactoryProvider,
    storageHistograms: DbStorageHistograms,
    loggerFactory: NamedLoggerFactory,
    timeouts: ProcessingTimeout,
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
          metricsFactoryProvider.generateMetricsFactory(metricsContext),
          storageHistograms,
          loggerFactory,
        )
      },
    )
  }

  def forSv(name: String): SvAppMetrics = {
    svs.getOrElseUpdate(
      name, {
        val metricsContext = MetricsContext("node_name" -> name, "node_type" -> "sv")
        new SvAppMetrics(
          metricsFactoryProvider.generateMetricsFactory(metricsContext),
          storageHistograms,
          loggerFactory,
        )
      },
    )
  }

  def forScan(name: String): ScanAppMetrics = {
    scans.getOrElseUpdate(
      name, {
        val metricsContext = MetricsContext("node_name" -> name, "node_type" -> "scan")
        new ScanAppMetrics(
          metricsFactoryProvider.generateMetricsFactory(metricsContext),
          storageHistograms,
          loggerFactory,
          timeouts,
        )
      },
    )
  }

  def forSplitwell(name: String): SplitwellAppMetrics = {
    splitwells.getOrElseUpdate(
      name, {
        val metricsContext = MetricsContext("node_name" -> name, "node_type" -> "splitwell")
        new SplitwellAppMetrics(
          metricsFactoryProvider.generateMetricsFactory(metricsContext),
          storageHistograms,
          loggerFactory,
        )
      },
    )
  }
}
