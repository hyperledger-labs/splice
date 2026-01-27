// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.splitwell.metrics

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.logging.NamedLoggerFactory
import org.lfdecentralizedtrust.splice.BaseSpliceMetrics
import com.digitalasset.canton.metrics.DbStorageHistograms

/** Modelled after [[com.digitalasset.canton.synchronizer.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class SplitwellAppMetrics(
    metricsFactory: LabeledMetricsFactory,
    storageHistograms: DbStorageHistograms,
    loggerFactory: NamedLoggerFactory,
) extends BaseSpliceMetrics("splitwell", metricsFactory, storageHistograms, loggerFactory) {}
