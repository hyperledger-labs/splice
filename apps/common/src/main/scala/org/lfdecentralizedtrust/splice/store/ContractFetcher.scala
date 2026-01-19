// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.OptionT
import com.daml.ledger.api.v2.event.CreatedEvent.toJavaProto
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.BaseLedgerConnection
import org.lfdecentralizedtrust.splice.store.MultiDomainAcsStore.ContractCompanion
import org.lfdecentralizedtrust.splice.util.Contract

import scala.concurrent.{ExecutionContext, Future}

trait ContractFetcher {

  def lookupContractById[C, TCid <: ContractId[?], T](
      companion: C
  )(id: ContractId[?])(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[Contract[TCid, T]]]

}

object ContractFetcher {

  private class StoreContractFetcher(store: AppStore)(implicit ec: ExecutionContext)
      extends ContractFetcher {
    override def lookupContractById[C, TCid <: ContractId[?], T](
        companion: C
    )(id: ContractId[?])(implicit
        companionClass: ContractCompanion[C, TCid, T],
        traceContext: TraceContext,
    ): Future[Option[Contract[TCid, T]]] =
      store.multiDomainAcsStore.lookupContractById(companion)(id).map(_.map(_.contract))
  }

  private class StoreContractFetcherWithLedgerFallback(
      store: AppStore,
      fallbackLedgerClient: BaseLedgerConnection,
  )(implicit ec: ExecutionContext)
      extends ContractFetcher {
    override def lookupContractById[C, TCid <: ContractId[?], T](
        companion: C
    )(id: ContractId[?])(implicit
        companionClass: ContractCompanion[C, TCid, T],
        traceContext: TraceContext,
    ): Future[Option[Contract[TCid, T]]] =
      OptionT(store.multiDomainAcsStore.lookupContractById(companion)(id))
        .map(_.contract)
        .orElse(
          OptionT(fallbackLedgerClient.getContract(id, Seq(store.multiDomainAcsStore.storeParty)))
            .subflatMap { createdEvent =>
              companionClass
                .fromCreatedEvent(companion)(CreatedEvent.fromProto(toJavaProto(createdEvent)))
            }
        )
        .value
  }

  def createStoreWithLedgerFallback(
      enabled: Boolean,
      store: AppStore,
      fallbackLedgerClient: BaseLedgerConnection,
  )(implicit ec: ExecutionContext): ContractFetcher = {
    if (enabled) {
      new StoreContractFetcherWithLedgerFallback(store, fallbackLedgerClient)
    } else {
      new StoreContractFetcher(store)
    }
  }

}
