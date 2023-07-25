package com.daml.network.util

import com.digitalasset.canton.logging.pretty.PrettyPrinting
import com.digitalasset.canton.topology.DomainId

import PrettyInstances.*

/** A contract that is ready to be acted upon
  * on the given domain.
  */
final case class AssignedContract[TCid, T](
    contract: Contract[TCid, T],
    domain: DomainId,
) extends PrettyPrinting
    with Contract.Has[TCid, T] {
  override def pretty = prettyOfClass[AssignedContract[TCid, T]](
    param("contract", _.contract),
    param("domain", _.domain),
  )
}
