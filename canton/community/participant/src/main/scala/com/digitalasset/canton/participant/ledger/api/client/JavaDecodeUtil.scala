// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.ledger.api.client

import com.daml.ledger.javaapi.data.codegen.{Contract, ContractCompanion, ContractId}
import com.daml.ledger.javaapi.data.{CreatedEvent, Template}

object JavaDecodeUtil {
  def decodeCreated[TC <: Contract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(event: CreatedEvent): Option[TC] =
    if (event.getTemplateId == companion.TEMPLATE_ID) {
      Some(companion.fromCreatedEvent(event))
    } else None
}
