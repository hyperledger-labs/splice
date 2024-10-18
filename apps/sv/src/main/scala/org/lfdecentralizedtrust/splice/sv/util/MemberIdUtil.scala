// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.util

import com.digitalasset.canton.topology

object MemberIdUtil {

  object MediatorId {

    def tryFromProtoPrimitive(mediatorId: String, field: String): topology.MediatorId = {
      topology.MediatorId
        .fromProtoPrimitive(mediatorId, field)
        .fold(
          err =>
            throw new IllegalArgumentException(
              s"Failed to parse mediator id from $mediatorId, with field $field: ${err.message}"
            ),
          identity,
        )
    }

  }

  object SequencerId {

    def tryFromProtoPrimitive(sequencerId: String, field: String): topology.SequencerId = {
      topology.SequencerId
        .fromProtoPrimitive(sequencerId, field)
        .fold(
          err =>
            throw new IllegalArgumentException(
              s"Failed to parse sequencer id from $sequencerId, with field $field: ${err.message}"
            ),
          identity,
        )
    }

  }

}
