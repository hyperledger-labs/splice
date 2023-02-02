package com.daml.network.sv.store

import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.topology.PartyId

object SvStore {

  /** Key used in both SV stores. */
  case class Key(
      /** The party-id of the SV that this store belongs to. */
      svParty: PartyId,
      /** The party-id of the SVC issuing CC accepted by this provider. */
      svcParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("svParty", _.svParty),
      param("svcParty", _.svcParty),
    )
  }
}
