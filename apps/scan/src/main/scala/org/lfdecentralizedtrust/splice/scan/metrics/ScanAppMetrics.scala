// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.scan.metrics

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.metrics.DbStorageHistograms
import org.lfdecentralizedtrust.splice.BaseSpliceMetrics
import org.lfdecentralizedtrust.splice.scan.store.db.DbScanStoreMetrics

/** Modelled after [[com.digitalasset.canton.synchronizer.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class ScanAppMetrics(
    metricsFactory: LabeledMetricsFactory,
    storageHistograms: DbStorageHistograms,
    loggerFactory: NamedLoggerFactory,
    timeouts: ProcessingTimeout,
) extends BaseSpliceMetrics("scan", metricsFactory, storageHistograms, loggerFactory) {
  val dbScanStore = new DbScanStoreMetrics(metricsFactory, loggerFactory, timeouts)
  val verdictIngestion = new ScanMediatorVerdictIngestionMetrics(metricsFactory)
  val trafficIngestion = new ScanSequencerTrafficIngestionMetrics(metricsFactory)
}
