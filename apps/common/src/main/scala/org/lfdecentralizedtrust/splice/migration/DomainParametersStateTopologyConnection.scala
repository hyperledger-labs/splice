// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import cats.data.OptionT
import cats.instances.future.*
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.transaction.SynchronizerParametersState
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
class SynchronizerParametersStateTopologyConnection(connection: TopologyAdminConnection) {

  // Selects the topology transaction that has the highest serial number and the smallest number of signatories.
  // This ensures that everyone will choose the same timestamp as to when the domain was paused.
  def firstAuthorizedStateForTheLatestSynchronizerParametersState(
      domain: SynchronizerId
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, PausedSynchronizersState] = for {
    domainParamsHistory <- OptionT.liftF(
      connection
        .listSynchronizerParametersStateHistory(domain)
    )
    pausedState <- OptionT.fromOption {
      val latestState = domainParamsHistory.map(_.base.serial).maxOption
      domainParamsHistory
        .filter(state => latestState.contains(state.base.serial))
        .minByOption(_.base.validFrom)
    }
    lastUnpaused <- OptionT.fromOption {
      domainParamsHistory
        .filter(p =>
          p.mapping.parameters.confirmationRequestsMaxRate > NonNegativeInt.zero && p.mapping.parameters.mediatorReactionTimeout > com.digitalasset.canton.time.NonNegativeFiniteDuration.Zero
        )
        .maxByOption(_.base.validFrom)
    }
  } yield PausedSynchronizersState(
    pausedState,
    lastUnpaused,
  )
}

final case class PausedSynchronizersState(
    pausedState: TopologyAdminConnection.TopologyResult[
      SynchronizerParametersState
    ],
    lastUnpausedState: TopologyAdminConnection.TopologyResult[SynchronizerParametersState],
) {
  def exportTimestamp: Instant = {
    require(pausedState.mapping.parameters.confirmationRequestsMaxRate == NonNegativeInt.zero)
    require(
      pausedState.mapping.parameters.mediatorReactionTimeout == com.digitalasset.canton.time.NonNegativeFiniteDuration.Zero
    )
    pausedState.base.validFrom
  }
  def acsExportWaitTimestamp: Instant = {
    // At exportTimestamp we set mediatorReactionTimeout = 0. This means any confirmation request after exportTimestamp
    // will fail. Confirmation requests before exportTimestamp can take confirmationResponseTimeout + mediatorReactionTimeout to time out
    // so if we wait until then we know that there are no more in-flight requests.
    exportTimestamp
      .plus(lastUnpausedState.mapping.parameters.confirmationResponseTimeout.duration)
      .plus(lastUnpausedState.mapping.parameters.mediatorReactionTimeout.duration)
  }
}
