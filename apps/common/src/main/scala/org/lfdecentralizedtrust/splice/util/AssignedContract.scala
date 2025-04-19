// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import org.lfdecentralizedtrust.splice.http.v0.definitions as http
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import com.digitalasset.canton.logging.ErrorLoggingContext
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.topology.SynchronizerId
import PrettyInstances.*

/** A contract that is ready to be acted upon
  * on the given domain.
  */
final case class AssignedContract[TCid, T](
    contract: Contract[TCid, T],
    domain: SynchronizerId,
) extends PrettyPrinting
    with Contract.Has[TCid, T] {
  override def pretty: Pretty[AssignedContract[TCid, T]] = prettyOfClass[AssignedContract[TCid, T]](
    param("contract", _.contract),
    param("domain", _.domain),
  )

  /** [[ContractWithState]] is strictly wider than this type. */
  def toContractWithState: ContractWithState[TCid, T] =
    ContractWithState(contract, ContractState.Assigned(domain))

  def toHttp(implicit elc: ErrorLoggingContext): http.AssignedContract =
    http.AssignedContract(
      contract.toHttp,
      Codec.encode(domain),
    )
}

object AssignedContract {

  def fromHttp[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Contract.Companion.Template[TCid, T]
  )(contract: http.AssignedContract)(implicit
      decoder: TemplateJsonDecoder
  ): Either[String, AssignedContract[TCid, T]] =
    for {
      decodedContract <- Contract.fromHttp(companion)(contract.contract).left.map(_.toString)
      synchronizerId <- Codec.decode(Codec.SynchronizerId)(contract.domainId)
    } yield AssignedContract(
      decodedContract,
      synchronizerId,
    )
}
