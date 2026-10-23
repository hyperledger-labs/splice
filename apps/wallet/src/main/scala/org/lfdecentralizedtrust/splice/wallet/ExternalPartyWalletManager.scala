// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.wallet

import com.digitalasset.canton.lifecycle.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.Mutex
import com.digitalasset.canton.util.ShowUtil.*
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer
import org.lfdecentralizedtrust.splice.config.{AutomationConfig, SpliceParametersConfig}
import org.lfdecentralizedtrust.splice.environment.{
  PackageVersionSupport,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.scan.admin.api.client.BftScanConnection
import org.lfdecentralizedtrust.splice.store.{DomainTimeSynchronization, LimitHelpers}
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import org.lfdecentralizedtrust.splice.wallet.config.RewardSharingConfig
import org.lfdecentralizedtrust.splice.wallet.store.{ExternalPartyWalletStore, WalletStore}

import scala.collection.concurrent.TrieMap
import scala.concurrent.{blocking, ExecutionContext}
import scala.util.Failure
import scala.util.control.NonFatal

/** Manages all services comprising an external party wallets. */
class ExternalPartyWalletManager(
    ledgerClient: SpliceLedgerClient,
    val store: WalletStore,
    val validatorUser: String,
    automationConfig: AutomationConfig,
    private[splice] val clock: Clock,
    domainTimeSync: DomainTimeSynchronization,
    storage: DbStorage,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
    migrationId: Long,
    participantId: ParticipantId,
    params: SpliceParametersConfig,
    scanConnection: BftScanConnection,
    packageVersionSupport: PackageVersionSupport,
    rewardSharingConfigByParty: Map[String, RewardSharingConfig],
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
    templateJsonDecoder: TemplateJsonDecoder,
    closeContext: CloseContext,
) extends AutoCloseable
    with NamedLogging
    with LimitHelpers {

  // map from externalParty party to external party wallet service
  private[this] val externalPartyWalletsMap
      : scala.collection.concurrent.Map[PartyId, (RetryProvider, ExternalPartyWalletService)] =
    TrieMap.empty
  private val mutex = Mutex()

  // Note: putIfAbsent() eagerly evaluates the value to be inserted, but we only want to start
  // the new service if there is no existing service for the party yet.
  // Accessing a concurrent map while modifying it is safe, so we only need to synchronize adding
  // and removing parties.
  private[this] def addExternalPartytWallet(
      externalParty: PartyId,
      externalPartyRetryProvider: RetryProvider,
      externalPartyStore: ExternalPartyWalletStore,
  ): Option[(RetryProvider, ExternalPartyWalletService)] = blocking {
    mutex.exclusive {
      if (externalPartyWalletsMap.contains(externalParty)) {
        logger.debug(
          show"Wallet for external party ${externalParty} already exists, not creating a new one."
        )(TraceContext.empty)
        None
      } else {
        logger.debug(
          show"Creating wallet service and retry provider for external party ${externalParty}."
        )(TraceContext.empty)
        val externalPartyWallet = createExternalPartytWallet(
          externalParty,
          externalPartyRetryProvider,
          externalPartyStore,
        )
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
  )(implicit tc: TraceContext): FutureUnlessShutdown[Boolean] = {
    if (retryProvider.isClosing) {
      FutureUnlessShutdown.abortedDueToShutdown
    } else if (externalPartyWalletsMap.contains(party)) {
      logger.debug(
        show"Wallet for external party ${party} already exists, not creating a new one."
      )
      FutureUnlessShutdown.pure(false)
    } else {
      val key = ExternalPartyWalletStore.Key(
        dsoParty = store.walletKey.dsoParty,
        store.walletKey.validatorParty,
        party,
      )
      val partyLoggerFactory = loggerFactory.append("externalParty", key.externalParty.toString)
      val externalPartyRetryProvider =
        RetryProvider(
          partyLoggerFactory,
          retryProvider.timeouts,
          retryProvider.futureSupervisor,
          retryProvider.metricsFactory,
        )
      val partyStoreF = ExternalPartyWalletStore(
        key,
        storage,
        partyLoggerFactory,
        externalPartyRetryProvider,
        migrationId,
        participantId,
        automationConfig.ingestion,
        params.defaultLimit,
      ).transform {
        case Failure(e) =>
          externalPartyRetryProvider.close()
          Failure(e)
        case success => success
      }
      FutureUnlessShutdown.outcomeF(partyStoreF).flatMap { partyStore =>
        // If creating the service fails, the store and the retry provider are closed there
        val externalPartyRetryProviderAndWalletService =
          addExternalPartytWallet(party, externalPartyRetryProvider, partyStore)
        if (externalPartyRetryProviderAndWalletService.isEmpty) {
          // Lost a race with a concurrent creation of the same party's wallet
          try partyStore.close()
          finally externalPartyRetryProvider.close()
        }

        // There might have been a concurrent call to .close() that missed the above addition of this externalParty
        if (retryProvider.isClosing) {
          logger.debug(
            show"Detected race between adding wallet for party ${party} and shutdown: closing wallet."
          )
          externalPartyRetryProviderAndWalletService.foreach {
            case (externalPartyRetryProvider, walletService) =>
              try externalPartyRetryProvider.close()
              finally walletService.close()
          }
          FutureUnlessShutdown.abortedDueToShutdown
        } else {
          FutureUnlessShutdown.pure(externalPartyRetryProviderAndWalletService.isDefined)
        }
      }
    }
  }

  private def createExternalPartytWallet(
      externalParty: PartyId,
      externalPartyRetryProvider: RetryProvider,
      externalPartyStore: ExternalPartyWalletStore,
  ): (RetryProvider, ExternalPartyWalletService) = {
    val partyLoggerFactory = loggerFactory.append("externalParty", externalParty.toString)
    val walletService =
      try {
        new ExternalPartyWalletService(
          ledgerClient,
          externalPartyStore,
          automationConfig,
          clock,
          domainTimeSync,
          externalPartyRetryProvider,
          partyLoggerFactory,
          params,
          scanConnection,
          packageVersionSupport,
          rewardSharingConfigByParty.getOrElse(
            externalParty.toProtoPrimitive,
            RewardSharingConfig.BuiltIn(),
          ),
        )
      } catch {
        case NonFatal(e) =>
          externalPartyRetryProvider.close()
          throw e
      }
    (externalPartyRetryProvider, walletService)
  }

  override def close(): Unit = LifeCycle.close(
    // per-party retry providers should have been closed by the shutdown signal, so only closing the services here
    externalPartyWalletsMap.values.map(_._2).toSeq*
  )(logger)
}
