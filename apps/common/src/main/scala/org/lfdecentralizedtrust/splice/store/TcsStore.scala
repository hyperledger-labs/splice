// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.util.ContractWithState

import scala.concurrent.Future

trait TcsStore {

  def lookupContractByIdAsOf[C, TCid <: ContractId[?], T](
      companion: C
  )(id: ContractId[?], asOf: CantonTimestamp, synchronizerId: SynchronizerId)(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[ContractWithState[TCid, T]]]

  /** List ALL contracts of the given template that are active as of the given
    * record time. Use this with great caution as the result set is unbounded in size.
    */
  def listAllContractsAsOf[C, TCid <: ContractId[?], T](
      companion: C,
      asOf: CantonTimestamp,
      synchronizerId: SynchronizerId,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]]

  /** Returns all contracts whose activeness interval intersects with
    * [lowerBoundIncl, upperBoundIncl]. Use this with great caution as the
    * result set is unbounded in size.
    */
  def listAllContractsActiveWithin[C, TCid <: ContractId[?], T](
      companion: C,
      lowerBoundIncl: CantonTimestamp,
      upperBoundIncl: CantonTimestamp,
      synchronizerId: SynchronizerId,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[TcsStore.TemporalContractWithState[TCid, T]]]
}

object TcsStore {

  /** A contract with its temporal bounds from the TCS range query. */
  case class TemporalContractWithState[TCid, T](
      contractWithState: ContractWithState[TCid, T],
      createdAt: CantonTimestamp,
      archivedAt: Option[CantonTimestamp],
  )

  /** Pure function: filter contracts alive at a specific timestamp. */
  def contractsActiveAsOf[TCid, T](
      contracts: Seq[TemporalContractWithState[TCid, T]],
      asOf: CantonTimestamp,
  ): Seq[ContractWithState[TCid, T]] =
    contracts.collect {
      case tc if tc.createdAt <= asOf && tc.archivedAt.forall(_ > asOf) =>
        tc.contractWithState
    }
}
