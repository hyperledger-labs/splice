package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.topology.PartyId

trait AnsTestUtil {
  protected def expectedAns(partyId: PartyId, entry: String) = {
    s"${entry} (${partyId.toProtoPrimitive})"
  }
}
