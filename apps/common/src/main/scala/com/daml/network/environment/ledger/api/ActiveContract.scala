// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.environment.ledger.api

import com.daml.ledger.api.v2.event as scalaEvent
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.network.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.daml.ledger.api.v2.state_service as lapi
import com.digitalasset.canton.topology.DomainId

final case class ActiveContract(
    domainId: DomainId,
    createdEvent: CreatedEvent,
    reassignmentCounter: Long,
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("domainId", _.domainId),
      param("createdEvent", _.createdEvent),
      param("reassignmentCounter", _.reassignmentCounter),
    )
}

object ActiveContract {
  def fromProto(proto: lapi.ActiveContract): ActiveContract = {
    ActiveContract(
      DomainId.tryFromString(proto.domainId),
      CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
      proto.reassignmentCounter,
    )
  }
}
