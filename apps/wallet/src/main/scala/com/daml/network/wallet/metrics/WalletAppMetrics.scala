package com.daml.network.wallet.metrics

import com.codahale.metrics.MetricRegistry
import com.daml.metrics.api.MetricName
import com.digitalasset.canton.metrics.DbStorageMetrics
import com.digitalasset.canton.metrics.MetricHandle.NodeMetrics

/** Modelled after [[com.digitalasset.canton.participant.metrics.ParticipantMetrics]].
  *
  * This is only a bare-bones implementation so the code compiles so far.
  */
class WalletAppMetrics(override val prefix: MetricName, override val registry: MetricRegistry)
    extends NodeMetrics {
  object dbStorage extends DbStorageMetrics(prefix, registry)
}
