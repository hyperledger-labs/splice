// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import io.grpc.Status
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyconfigstate.ExternalPartyConfigState
import org.lfdecentralizedtrust.splice.util.Contract
import com.digitalasset.canton.logging.pretty.{Pretty, PrettyPrinting}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

trait ExternalPartyConfigStateStore extends AppStore {

  def lookupExternalPartyConfigStatesPair()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[ExternalPartyConfigStateStore.ExternalPartyConfigStatePair]] =
    for {
      configStates <- multiDomainAcsStore.listContracts(
        ExternalPartyConfigState.COMPANION
      )
    } yield for {
      Seq(oldest, newest) <- Some(
        configStates
          .sortBy(_.payload.targetArchiveAfter)
      )
    } yield ExternalPartyConfigStateStore.ExternalPartyConfigStatePair(
      oldest = oldest.contract,
      newest = newest.contract,
    )

  def getExternalPartyConfigStatesPair()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[ExternalPartyConfigStateStore.ExternalPartyConfigStatePair] =
    lookupExternalPartyConfigStatesPair(
    ).map(
      _.getOrElse(
        throw Status.NOT_FOUND
          .withDescription("No active ExternalPartyConfigState pair")
          .asRuntimeException
      )
    )

  def lookupLatestExternalPartyConfigState()(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Contract[ExternalPartyConfigState.ContractId, ExternalPartyConfigState]]] =
    lookupExternalPartyConfigStatesPair().map(_.map(_.newest))
}

object ExternalPartyConfigStateStore {
  case class ExternalPartyConfigStatePair(
      oldest: Contract[ExternalPartyConfigState.ContractId, ExternalPartyConfigState],
      newest: Contract[ExternalPartyConfigState.ContractId, ExternalPartyConfigState],
  ) extends PrettyPrinting {
    override def pretty: Pretty[this.type] =
      prettyOfClass(
        param("oldest", _.oldest),
        param("newest", _.newest),
      )

    def toSeq = Seq(oldest, newest)
  }

}
