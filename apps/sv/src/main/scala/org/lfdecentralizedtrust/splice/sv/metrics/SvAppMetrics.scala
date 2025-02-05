// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.metrics

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import org.lfdecentralizedtrust.splice.BaseSpliceMetrics
import com.digitalasset.canton.metrics.DbStorageHistograms

/** Modelled after [[com.digitalasset.canton.synchronizer.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class SvAppMetrics(
    metricsFactory: LabeledMetricsFactory,
    storageHistograms: DbStorageHistograms,
) extends BaseSpliceMetrics("sv", metricsFactory, storageHistograms) {}
