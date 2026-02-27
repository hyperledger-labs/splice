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

  def listContractsAsOf[C, TCid <: ContractId[?], T](
      companion: C,
      asOf: CantonTimestamp,
      synchronizerId: SynchronizerId,
      limit: Limit,
  )(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Seq[ContractWithState[TCid, T]]]
}
