// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.traverse.*
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractState
import com.daml.ledger.javaapi.data.codegen.{ContractId, DamlRecord}
import com.digitalasset.canton.ProtoDeserializationError
import com.digitalasset.canton.logging.pretty.Pretty
import com.digitalasset.canton.topology.DomainId
import org.lfdecentralizedtrust.splice.http.v0.definitions
import Contract.Companion
import com.digitalasset.canton.logging.ErrorLoggingContext

final case class ContractWithState[TCid, T](
    contract: Contract[TCid, T],
    state: ContractState,
) extends Contract.Has[TCid, T] {
  def toAssignedContract: Option[AssignedContract[TCid, T]] =
    state match {
      case ContractState.Assigned(domain) => Some(AssignedContract(contract, domain))
      case ContractState.InFlight => None
    }

  def toHttp(implicit elc: ErrorLoggingContext): definitions.ContractWithState =
    definitions.ContractWithState(
      contract.toHttp,
      state.fold(domainId => Some(domainId.toProtoPrimitive), None),
    )
}

object ContractWithState {
  implicit val pretty: Pretty[ContractWithState[?, ?]] = {
    import Pretty.*
    prettyOfClass[ContractWithState[?, ?]](
      param("contract", _.contract),
      param("state", _.state),
    )
  }

  import org.lfdecentralizedtrust.splice.http.v0.definitions.{
    MaybeCachedContract,
    MaybeCachedContractWithState,
  }

  def handleMaybeCached[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(
      cachedValue: Option[ContractWithState[TCid, T]],
      maybeCached: MaybeCachedContractWithState,
  )(implicit decoder: TemplateJsonDecoder): Either[String, ContractWithState[TCid, T]] = {
    import ContractState.*
    for {
      contract <- Contract.handleMaybeCachedContract(companion)(
        cachedValue map (_.contract),
        MaybeCachedContract(maybeCached.contract),
      )
      state <- maybeCached.domainId.traverse(d => DomainId fromString d map Assigned)
    } yield ContractWithState(contract, state getOrElse InFlight)
  }

  def fromHttp[TCid <: ContractId[T], T <: DamlRecord[?]](
      companion: Companion.Template[TCid, T]
  )(http: definitions.ContractWithState)(implicit
      decoder: TemplateJsonDecoder
  ): Either[ProtoDeserializationError, ContractWithState[TCid, T]] = {
    for {
      contract <- Contract.fromHttp(companion)(http.contract)
      state <- http.domainId
        .traverse(d => DomainId.fromString(d).map(ContractState.Assigned))
        .left
        .map(err => ProtoDeserializationError.ValueConversionError("domainId", err))
    } yield ContractWithState(
      contract,
      state.getOrElse(ContractState.InFlight),
    )
  }
}
