package com.daml.network.sv.onboarding

import com.daml.network.environment.{CNLedgerClient, ParticipantAdminConnection, RetryProvider}
import com.daml.network.sv.LocalDomainNode
import com.daml.network.sv.automation.{SvSvAutomationService, SvSvcAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvStore, SvSvStore, SvSvcStore}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

trait NodeInitializerUtil extends NamedLogging {

  protected val config: SvAppBackendConfig
  protected val storage: Storage
  protected val retryProvider: RetryProvider
  protected val clock: Clock
  protected val participantAdminConnection: ParticipantAdminConnection
  protected val cometBftNode: Option[CometBftNode]
  protected val ledgerClient: CNLedgerClient

  protected def newSvStore(
      key: SvStore.Key,
      // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
      domainMigrationId: Long,
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvStore = SvSvStore(
    key,
    storage,
    loggerFactory,
    retryProvider,
    domainMigrationId,
  )

  protected def newSvSvAutomationService(
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      ledgerClient: CNLedgerClient,
  )(implicit
      ec: ExecutionContextExecutor,
      mat: Materializer,
      tracer: Tracer,
  ) =
    new SvSvAutomationService(
      clock,
      config,
      svStore,
      svcStore,
      ledgerClient,
      retryProvider,
      loggerFactory,
    )

  protected def newSvcStore(
      key: SvStore.Key,
      // TODO(#9731): get migration id from sponsor sv / scan instead of configuring here
      domainMigrationId: Long,
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvcStore = {
    SvSvcStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationId,
    )
  }

  protected def newSvSvcAutomationService(
      svStore: SvSvStore,
      svcStore: SvSvcStore,
      localDomainNode: Option[LocalDomainNode],
  )(implicit
      ec: ExecutionContextExecutor,
      mat: Materializer,
      tracer: Tracer,
  ) =
    new SvSvcAutomationService(
      clock,
      config,
      svStore,
      svcStore,
      ledgerClient,
      participantAdminConnection,
      retryProvider,
      cometBftNode,
      localDomainNode,
      loggerFactory,
    )

  protected def newSvcPartyHosting(
      storeKey: SvStore.Key
  )(implicit ec: ExecutionContextExecutor) = new SvcPartyHosting(
    participantAdminConnection,
    storeKey.svcParty,
    retryProvider,
    loggerFactory,
  )

}
