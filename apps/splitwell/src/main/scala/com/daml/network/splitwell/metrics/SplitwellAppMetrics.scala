// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.splitwell.metrics

import com.daml.network.BaseSpliceMetrics
import com.digitalasset.canton.metrics.CantonLabeledMetricsFactory

/** Modelled after [[com.digitalasset.canton.domain.metrics.DomainMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class SplitwellAppMetrics(
    metricsFactory: CantonLabeledMetricsFactory
) extends BaseSpliceMetrics("splitwell", metricsFactory) {}
