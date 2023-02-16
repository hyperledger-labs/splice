package com.daml.network.util

import com.daml.ledger.javaapi.data.CreatedEvent
import com.digitalasset.canton.topology.PartyId

object CreatedEventImplicits {
  implicit class CreatedEventSyntax(private val event: CreatedEvent) extends AnyVal {
    def hasStakeholder(party: PartyId): Boolean = {
      val p = party.toProtoPrimitive
      event.getSignatories.contains(p) || event.getObservers.contains(p)
    }
  }

}
