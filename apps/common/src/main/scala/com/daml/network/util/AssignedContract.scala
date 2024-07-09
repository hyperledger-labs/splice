// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.util

import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.daml.network.http.v0.definitions as http
import com.daml.network.store.MultiDomainAcsStore.ContractState
import com.digitalasset.canton.logging.ErrorLoggingContext
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
      domainId <- Codec.decode(Codec.DomainId)(contract.domainId)
    } yield AssignedContract(
      decodedContract,
      domainId,
    )
}
