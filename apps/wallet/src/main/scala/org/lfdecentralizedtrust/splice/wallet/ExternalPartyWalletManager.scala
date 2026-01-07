// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.{RetryProvider, SpliceLedgerClient}
import org.lfdecentralizedtrust.splice.migration.DomainMigrationInfo
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
  LimitHelpers,
}
import org.lfdecentralizedtrust.splice.util.{HasHealth, TemplateJsonDecoder}
import org.lfdecentralizedtrust.splice.wallet.store.{ExternalPartyWalletStore, WalletStore}
import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, blocking}

/** Manages all services comprising an external party wallets. */
class ExternalPartyWalletManager(
    ledgerClient: SpliceLedgerClient,
    val store: WalletStore,
    val validatorUser: String,
    automationConfig: AutomationConfig,
    private[splice] val clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    domainUnpausedSync: DomainUnpausedSynchronization,
    storage: DbStorage,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    domainMigrationInfo: DomainMigrationInfo,
    participantId: ParticipantId,
    ingestFromParticipantBegin: Boolean,
    ingestUpdateHistoryFromParticipantBegin: Boolean,
    params: SpliceParametersConfig,
    scanConnection: BftScanConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends AutoCloseable
    with NamedLogging
    with HasHealth
    with LimitHelpers {

  // map from externalParty party to external party wallet service
  private[this] val externalPartyWalletsMap
      : scala.collection.concurrent.Map[PartyId, (RetryProvider, ExternalPartyWalletService)] =
    TrieMap.empty

  // Note: putIfAbsent() eagerly evaluates the value to be inserted, but we only want to start
  // the new service if there is no existing service for the party yet.
  // Accessing a concurrent map while modifying it is safe, so we only need to synchronize adding parties.
  private[this] def addExternalPartytWallet(
      externalParty: PartyId,
      createWallet: PartyId => (RetryProvider, ExternalPartyWalletService),
  ): Option[(RetryProvider, ExternalPartyWalletService)] = blocking {
    this.synchronized {
      if (externalPartyWalletsMap.contains(externalParty)) {
        logger.debug(
          show"Wallet for external party ${externalParty} already exists, not creating a new one."
        )(TraceContext.empty)
        None
      } else {
        logger.debug(
          show"Creating wallet service and retry provider for external party ${externalParty}."
        )(TraceContext.empty)
        val externalPartyWallet = createWallet(externalParty)
        externalPartyWalletsMap.put(externalParty, externalPartyWallet): Unit
        Some(externalPartyWallet)
      }
    }
  }

  retryProvider.runOnShutdownWithPriority_(new RunOnClosing {
    override def name = s"set per-party retry providers as closed"
    override def done = false
    override def run()(implicit tc: TraceContext) = {
      externalPartyWalletsMap.values.foreach { case (externalPartyRetryProvider, _) =>
        externalPartyRetryProvider.setAsClosing()
      }
    }
  })

  retryProvider.runOnOrAfterClose_(new RunOnClosing {
    override def name = s"shutdown per-party retry providers"
    // this is not perfectly precise, but RetryProvider.close is idempotent
    override def done = false
    override def run()(implicit tc: TraceContext) = {
      externalPartyWalletsMap.values.foreach { case (externalPartyRetryProvider, _) =>
        externalPartyRetryProvider.close()
      }
    }
  })(TraceContext.empty)

  final def lookupExternalPartyWallet(
      externalParty: PartyId
  ): Option[ExternalPartyWalletService] =
    externalPartyWalletsMap.get(externalParty).map(_._2)

  /** Get or create the store for an external party. Intended to be called when an external party is onboarded.
    *
    * Do not use this in request handlers to avoid leaking resources.
    *
    * @return true, if a new external party wallet was created
    */
  final def getOrCreateWallet(
      party: PartyId
  ): UnlessShutdown[Boolean] = {
    if (retryProvider.isClosing) {
      UnlessShutdown.AbortedDueToShutdown
    } else {

      val externalPartyRetryProviderAndWalletService =
        addExternalPartytWallet(party, createExternalPartytWallet)

      // There might have been a concurrent call to .close() that missed the above addition of this externalParty
      if (retryProvider.isClosing) {
        logger.debug(
          show"Detected race between adding wallet for party ${party} and shutdown: closing wallet."
        )(TraceContext.empty)
        externalPartyRetryProviderAndWalletService.foreach {
          case (externalPartyRetryProvider, walletService) =>
            externalPartyRetryProvider.close()
            walletService.close()
        }
        UnlessShutdown.AbortedDueToShutdown
      } else {
        UnlessShutdown.Outcome(externalPartyRetryProviderAndWalletService.isDefined)
      }
    }
  }

  private def createExternalPartytWallet(
      externalParty: PartyId
  ): (RetryProvider, ExternalPartyWalletService) = {
    val key = ExternalPartyWalletStore.Key(
      dsoParty = store.walletKey.dsoParty,
      store.walletKey.validatorParty,
      externalParty,
    )
    val partyLoggerFactory = loggerFactory.append("externalParty", key.externalParty.toString)
    val externalPartyRetryProvider =
      RetryProvider(
        partyLoggerFactory,
        retryProvider.timeouts,
        retryProvider.futureSupervisor,
        retryProvider.metricsFactory,
      )
    val walletService = new ExternalPartyWalletService(
      ledgerClient,
      key,
      automationConfig,
      clock,
      domainTimeSync,
      domainUnpausedSync,
      storage,
      externalPartyRetryProvider,
      partyLoggerFactory,
      domainMigrationInfo,
      participantId,
      ingestFromParticipantBegin,
      ingestUpdateHistoryFromParticipantBegin,
      params,
      scanConnection,
    )
    (externalPartyRetryProvider, walletService)
  }

  override def isHealthy: Boolean = externalPartyWalletsMap.values.forall(_._2.isHealthy)

  override def close(): Unit = LifeCycle.close(
    // per-party retry providers should have been closed by the shutdown signal, so only closing the services here
    externalPartyWalletsMap.values.map(_._2).toSeq*
  )(logger)
}
