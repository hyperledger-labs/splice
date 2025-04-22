// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment.ledger.api

import com.daml.ledger.api.v2.event as scalaEvent
import com.daml.ledger.javaapi.data.CreatedEvent
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.daml.ledger.api.v2.state_service as lapi
import com.digitalasset.canton.topology.SynchronizerId

final case class ActiveContract(
    synchronizerId: SynchronizerId,
    createdEvent: CreatedEvent,
    reassignmentCounter: Long,
) extends PrettyPrinting {
  override def pretty: Pretty[this.type] =
    prettyOfClass(
      param("synchronizerId", _.synchronizerId),
      param("createdEvent", _.createdEvent),
      param("reassignmentCounter", _.reassignmentCounter),
    )
}

object ActiveContract {
  def fromProto(proto: lapi.ActiveContract): ActiveContract = {
    ActiveContract(
      SynchronizerId.tryFromString(proto.synchronizerId),
      CreatedEvent.fromProto(scalaEvent.CreatedEvent.toJavaProto(proto.getCreatedEvent)),
      proto.reassignmentCounter,
    )
  }
}
