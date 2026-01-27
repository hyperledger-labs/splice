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

/** The RecordOrderPublisher might cause that some contracts are visible by validators' stores,
  * but not yet by SVs'. Then, when a validator requires some data from Scan that is not yet there,
  * it slows the workflow down.
  * This is particularly relevant in the token standard, where the TransferInstruction and the amuletallocation
  * might appear in the validators before Scans, and when they try to accept them, Scan fails to provide the necessary
  * ChoiceContext.
  * The purpose of this class then is to fallback to a direct ledger call when the store says a contract does not exist.
  *
  * This is not general purpose (see limitations below), but covers the case for the Token Standard contracts.
  */
trait ChoiceContextContractFetcher {

  def lookupContractById[C, TCid <: ContractId[?], T](
      companion: C
  )(id: ContractId[?])(implicit
      companionClass: ContractCompanion[C, TCid, T],
      traceContext: TraceContext,
  ): Future[Option[Contract[TCid, T]]]

}

object ChoiceContextContractFetcher {

  private class StoreChoiceContextContractFetcher(store: AppStore)(implicit ec: ExecutionContext)
      extends ChoiceContextContractFetcher {
    override def lookupContractById[C, TCid <: ContractId[?], T](
        companion: C
    )(id: ContractId[?])(implicit
        companionClass: ContractCompanion[C, TCid, T],
        traceContext: TraceContext,
    ): Future[Option[Contract[TCid, T]]] =
      store.multiDomainAcsStore.lookupContractById(companion)(id).map(_.map(_.contract))
  }

  private class StoreChoiceContextContractFetcherWithLedgerFallback(
      store: AppStore,
      fallbackLedgerClient: BaseLedgerConnection,
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
      getContractValidity: NonNegativeFiniteDuration,
  )(implicit ec: ExecutionContext)
      extends ChoiceContextContractFetcher {
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
            // `getContract` will return archived contracts (and thus missing from the store) until they have been pruned.
            // Thus, we verify that it was created not too long ago,
            // such that it means that what happened is the store didn't see it yet, but the ledger did.
            // (as opposed to the contract actually being archived)
            // This will give a false-positive for a contract that was quickly archived after creation.
            .filter { event =>
              val createdAt =
                event.createdAt.flatMap(CantonTimestamp.fromProtoTimestamp(_).toOption)
              val ignoreBefore = clock.now.minus(getContractValidity.asJava)
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
      enabled: Boolean = true,
      // RecordOrderPublisher + (created_at VS record_time skew)
      getContractValidity: NonNegativeFiniteDuration = NonNegativeFiniteDuration.ofSeconds(120L),
  )
  def createStoreWithLedgerFallback(
      config: StoreContractFetcherWithLedgerFallbackConfig,
      store: AppStore,
      fallbackLedgerClient: BaseLedgerConnection,
      clock: Clock,
      loggerFactory: NamedLoggerFactory,
  )(implicit ec: ExecutionContext): ChoiceContextContractFetcher = {
    if (config.enabled) {
      new StoreChoiceContextContractFetcherWithLedgerFallback(
        store,
        fallbackLedgerClient,
        clock,
        loggerFactory,
        config.getContractValidity,
      )
    } else {
      new StoreChoiceContextContractFetcher(store)
    }
  }

}
