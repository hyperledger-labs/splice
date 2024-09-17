// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.metrics

import com.daml.metrics.api.MetricsContext
import com.daml.network.scan.metrics.ScanAppMetrics
import com.daml.network.splitwell.metrics.SplitwellAppMetrics
import com.daml.network.sv.metrics.SvAppMetrics
import com.daml.network.validator.metrics.ValidatorAppMetrics
import com.digitalasset.canton.metrics.{DbStorageHistograms, MetricsFactoryProvider}

import scala.collection.concurrent.TrieMap

case class SpliceMetricsFactory(
    metricsFactoryProvider: MetricsFactoryProvider,
    storageHistograms: DbStorageHistograms,
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
        )
      },
    )
  }

}
