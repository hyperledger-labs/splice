// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import cats.data.OptionT
import cats.instances.future.*
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection
import org.lfdecentralizedtrust.splice.util.SynchronizerMigrationUtil
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.transaction.SynchronizerParametersState
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
class SynchronizerParametersStateTopologyConnection(connection: TopologyAdminConnection) {

  def firstAuthorizedStateForTheLatestSynchronizerParametersState(
      domain: SynchronizerId
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, MigrationSynchronizersState] = for {
    domainParamsHistory <- OptionT.liftF(
      connection
        .listSynchronizerParametersStateHistory(domain)
    )
    // Selects the topology transaction that has the highest serial number and the smallest number of signatories.
    // This ensures that everyone will choose the same timestamp as to when the domain was paused.
    currentState <- OptionT.fromOption {
      val latestState = domainParamsHistory.map(_.base.serial).maxOption
      domainParamsHistory
        .filter(state => latestState.contains(state.base.serial))
        .minByOption(_.base.validFrom)
    }
    lastUnpaused <- OptionT.fromOption {
      domainParamsHistory
        .filter(SynchronizerMigrationUtil.synchronizerIsUnpaused(_))
        .maxByOption(_.base.validFrom)
    }
  } yield MigrationSynchronizersState(
    currentState,
    lastUnpaused,
  )
}

final case class MigrationSynchronizersState(
    currentState: TopologyAdminConnection.TopologyResult[
      SynchronizerParametersState
    ],
    lastUnpausedState: TopologyAdminConnection.TopologyResult[SynchronizerParametersState],
) {
  def exportTimestamp: Instant = {
    // We only check this here as some places invoke `firstAuthorizedStateForTheLatestSynchronizerParametersState`
    // to wait for the synchronizer to be paused so it is not always paused but when someone
    // wants to export, it definitely must be paused.
    require(SynchronizerMigrationUtil.synchronizerIsPaused(currentState))
    currentState.base.validFrom
  }
  def acsExportWaitTimestamp: Instant = {
    // At exportTimestamp we set mediatorReactionTimeout = 0 and confirmationResponseTimeout = 0. This means any confirmation request after exportTimestamp
    // will fail. Confirmation requests before exportTimestamp can take confirmationResponseTimeout + mediatorReactionTimeout to time out
    // so if we wait until then we know that there are no more in-flight requests.
    exportTimestamp
      .plus(lastUnpausedState.mapping.parameters.confirmationResponseTimeout.duration)
      .plus(lastUnpausedState.mapping.parameters.mediatorReactionTimeout.duration)
  }
}
