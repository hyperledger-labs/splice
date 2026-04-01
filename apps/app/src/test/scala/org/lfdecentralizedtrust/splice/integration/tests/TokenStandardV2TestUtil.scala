package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.lfdecentralizedtrust.splice.codegen.java.splice.api.token.holdingv2

trait TokenStandardV2TestUtil {

  def basicAccount(party: PartyId): holdingv2.Account =
    new holdingv2.Account(
      party.toProtoPrimitive,
      java.util.Optional.empty(),
      "",
    )

}
