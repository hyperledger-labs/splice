// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.store

import com.digitalasset.canton.logging.pretty.*
import com.digitalasset.canton.topology.PartyId

object SvStore {

  /** Key used in both SV stores. */
  case class Key(
      /** The party-id of the SV that this store belongs to. */
      svParty: PartyId,
      /** The party-id of the DSO issuing CC accepted by this provider. */
      dsoParty: PartyId,
  ) extends PrettyPrinting {
    override def pretty: Pretty[Key] = prettyOfClass(
      param("svParty", _.svParty),
      param("dsoParty", _.dsoParty),
    )
  }
}
