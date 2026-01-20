// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.store

import cats.data.OptionT
import com.daml.ledger.api.v2.event.CreatedEvent.toJavaProto
import com.daml.ledger.javaapi.data.CreatedEvent
import com.daml.ledger.javaapi.data.codegen.ContractId
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
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
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
      getContractValidity: NonNegativeFiniteDuration,
  )(implicit ec: ExecutionContext)
      extends ContractFetcher {
    private val logger = loggerFactory.getLogger(this.getClass)

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
            // `getContract` may return a contract that was archived (and thus missing from the store).
            // Thus, we verify that it was created not too long ago,
            // such that it means that what happened is the store didn't see it yet, but the ledger did.
            // (as opposed to the contract actually being archived)
            // This will give a false-positive for a contract that was quickly archived after creation.
            .filter { event =>
              val createdAt =
                event.createdAt.flatMap(CantonTimestamp.fromProtoTimestamp(_).toOption)
              val ignoreBefore = clock.now.minus(getContractValidity.asJava)
              logger
                .debug(s"For ContractId: $id: CreatedAt: $createdAt, ignoreBefore: $ignoreBefore")
              createdAt.exists(_ > ignoreBefore)
            }
            .subflatMap { createdEvent =>
              val javaCreatedEvent = CreatedEvent.fromProto(toJavaProto(createdEvent))
              logger.debug(s"Falling back to ledger for contract $javaCreatedEvent")
              companionClass
                .fromCreatedEvent(companion)(javaCreatedEvent)
            }
        )
        .value
  }

  case class StoreContractFetcherWithLedgerFallbackConfig(
      enabled: Boolean = false,
      // RecordOrderPublisher + (created_at VS record_time skew)
      getContractValidity: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(120L),
  )
  def createStoreWithLedgerFallback(
      config: StoreContractFetcherWithLedgerFallbackConfig,
      store: AppStore,
      fallbackLedgerClient: BaseLedgerConnection,
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): ContractFetcher = {
    if (config.enabled) {
      new StoreContractFetcherWithLedgerFallback(
        store,
        fallbackLedgerClient,
        clock,
        loggerFactory,
        config.getContractValidity,
      )
    } else {
      new StoreContractFetcher(store)
    }
  }

}
