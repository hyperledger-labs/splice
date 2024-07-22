// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.migration

import cats.data.OptionT
import com.daml.network.environment.TopologyAdminConnection
import com.digitalasset.canton.topology.DomainId
import com.digitalasset.canton.topology.transaction.DomainParametersState
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

@SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
class DomainParametersStateTopologyConnection(connection: TopologyAdminConnection) {

  // Selects the topology transaction that has the highest serial number and the smallest number of signatories.
  // This ensures that everyone will choose the same timestamp as to when the domain was paused.
  def firstAuthorizedStateForTheLatestDomainParametersState(
      domain: DomainId
  )(implicit
      tc: TraceContext,
      ec: ExecutionContext,
  ): OptionT[Future, TopologyAdminConnection.TopologyResult[
    DomainParametersState
  ]] = {
    OptionT(
      connection
        .listDomainParametersState(domain)
        .map { domainParamsHistory =>
          val latestState = domainParamsHistory.map(_.base.serial).maxOption
          domainParamsHistory
            .filter(state => latestState.contains(state.base.serial))
            .minByOption(_.base.validFrom)
        }
    )
  }

}
