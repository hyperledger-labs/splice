// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.protocol.DynamicSynchronizerParameters
import com.digitalasset.canton.time.NonNegativeFiniteDuration
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.{ForceFlag, ForceFlags}
import com.digitalasset.canton.topology.transaction.SynchronizerParametersState
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyResult
import scala.concurrent.Future

final object SynchronizerMigrationUtil {
  // We only check mediatorReactionTimeout here as that is what matters for safety and it ensures that
  // synchronizerIsUnpaused = !synchronizerIsUnpaused instead of checking that all 3 are 0 or both are non-zero.
  def synchronizerIsPaused(params: TopologyResult[SynchronizerParametersState]): Boolean =
    params.mapping.parameters.mediatorReactionTimeout == NonNegativeFiniteDuration.Zero

  def synchronizerIsUnpaused(params: TopologyResult[SynchronizerParametersState]): Boolean =
    !synchronizerIsPaused(params)

  def ensureSynchronizerIsPaused(
      topologyConnection: TopologyAdminConnection,
      synchronizerId: SynchronizerId,
  )(implicit tc: TraceContext): Future[TopologyResult[SynchronizerParametersState]] =
    topologyConnection.ensureDomainParameters(
      synchronizerId,
      _.tryUpdate(
        // confirmation requests max rate is really just set for better UX so transactions get rejected
        // instaed of timing out. It is only enforced on the
        // write path so there can still be transaction going through after we set it to zero
        // if the sequencer has not yet observed the topology change.
        // mediator reaction timeout and participant response timeout is enforced as part of the transaction protocol so there we really get a guarantee
        // that no transactions go through.
        confirmationRequestsMaxRate = NonNegativeInt.zero,
        mediatorReactionTimeout = NonNegativeFiniteDuration.Zero,
        // We want to block all transactions, ideally we would set this to zero but Canton doesn't like it when mediatorReactionTimeout + confirmationResponseTimeout = 0.
        // So instead we set this to 1 microsecond. This still makes it impossible to get a transaction through as
        // you need at a minimum two microseconds difference between confirmation request and verdict.
        confirmationResponseTimeout = NonNegativeFiniteDuration.tryOfMicros(1),
      ),
      forceChanges =
        ForceFlags(ForceFlag.AllowOutOfBoundsValue), // required for mediatorReactionTimeout = 0
    )

  def ensureSynchronizerIsUnpaused(
      topologyConnection: TopologyAdminConnection,
      synchronizerId: SynchronizerId,
  )(implicit tc: TraceContext): Future[TopologyResult[SynchronizerParametersState]] =
    topologyConnection.ensureDomainParameters(
      synchronizerId,
      _.tryUpdate(
        // hard code unpaused parameters for now as we don't change these parameters otherwise.
        confirmationRequestsMaxRate =
          DynamicSynchronizerParameters.defaultConfirmationRequestsMaxRate,
        mediatorReactionTimeout = DynamicSynchronizerParameters.defaultMediatorReactionTimeout,
        confirmationResponseTimeout =
          DynamicSynchronizerParameters.defaultConfirmationResponseTimeout,
      ),
    )

}
