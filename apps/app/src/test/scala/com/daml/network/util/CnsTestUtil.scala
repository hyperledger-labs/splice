package com.daml.network.util

import com.digitalasset.canton.topology.PartyId

trait CnsTestUtil {
  protected def expectedCns(partyId: PartyId, entry: String) = {
    s"${entry} (${partyId.toProtoPrimitive})"
  }
}
