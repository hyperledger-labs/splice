// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import cats.data.OptionT
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.topology.transaction.SynchronizerParametersState
import com.digitalasset.canton.tracing.TraceContext

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
  ): OptionT[Future, TopologyAdminConnection.TopologyResult[
    SynchronizerParametersState
  ]] = {
    OptionT(
      connection
        .listSynchronizerParametersState(domain)
        .map { domainParamsHistory =>
          val latestState = domainParamsHistory.map(_.base.serial).maxOption
          domainParamsHistory
            .filter(state => latestState.contains(state.base.serial))
            .minByOption(_.base.validFrom)
        }
    )
  }

}
