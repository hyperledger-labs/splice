// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.validator.metrics

import com.daml.metrics.api.MetricHandle.LabeledMetricsFactory
import com.digitalasset.canton.metrics.DbStorageHistograms
import org.lfdecentralizedtrust.splice.BaseSpliceMetrics

/** Modelled after [[com.digitalasset.canton.synchronizer.metrics.DomainMetrics]].
  */
class ValidatorAppMetrics(
    metricsFactory: LabeledMetricsFactory,
    storageHistograms: DbStorageHistograms,
) extends BaseSpliceMetrics("validator", metricsFactory, storageHistograms) {}
