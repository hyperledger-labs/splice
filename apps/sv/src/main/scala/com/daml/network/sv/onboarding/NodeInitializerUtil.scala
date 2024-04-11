package com.daml.network.sv.onboarding

import com.daml.network.environment.{CNLedgerClient, ParticipantAdminConnection, RetryProvider}
import com.daml.network.migration.DomainMigrationInfo
import com.daml.network.sv.LocalSynchronizerNode
import com.daml.network.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.sv.store.{SvDsoStore, SvStore, SvSvStore}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.lifecycle.CloseContext
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.resource.Storage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.ParticipantId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import io.opentelemetry.api.trace.Tracer
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import io.grpc.Status

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

trait NodeInitializerUtil extends NamedLogging with Spanning {

  protected val config: SvAppBackendConfig
  protected val storage: Storage
  protected val retryProvider: RetryProvider
  protected val clock: Clock
  protected val participantAdminConnection: ParticipantAdminConnection
  protected val cometBftNode: Option[CometBftNode]
  protected val ledgerClient: CNLedgerClient

  protected def newSvStore(
      key: SvStore.Key,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvSvStore = SvSvStore(
    key,
    storage,
    loggerFactory,
    retryProvider,
    domainMigrationInfo,
    participantId,
  )

  protected def newSvSvAutomationService(
      svStore: SvSvStore,
      dsoStore: SvDsoStore,
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
      dsoStore,
      ledgerClient,
      retryProvider,
      loggerFactory,
    )

  protected def newDsoStore(
      key: SvStore.Key,
      domainMigrationInfo: DomainMigrationInfo,
      participantId: ParticipantId,
  )(implicit
      ec: ExecutionContext,
      templateDecoder: TemplateJsonDecoder,
      closeContext: CloseContext,
  ): SvDsoStore = {
    SvDsoStore(
      key,
      storage,
      loggerFactory,
      retryProvider,
      domainMigrationInfo,
      participantId,
    )
  }

  protected def newSvDsoAutomationService(
      svStore: SvSvStore,
      dsoStore: SvDsoStore,
      localSynchronizerNode: Option[LocalSynchronizerNode],
  )(implicit
      ec: ExecutionContextExecutor,
      mat: Materializer,
      tracer: Tracer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateJsonDecoder: TemplateJsonDecoder,
  ) =
    new SvDsoAutomationService(
      clock,
      config,
      svStore,
      dsoStore,
      ledgerClient,
      participantAdminConnection,
      retryProvider,
      cometBftNode,
      localSynchronizerNode,
      loggerFactory,
    )

  protected def newDsoPartyHosting(
      storeKey: SvStore.Key
  )(implicit ec: ExecutionContextExecutor) = new DsoPartyHosting(
    participantAdminConnection,
    storeKey.dsoParty,
    retryProvider,
    loggerFactory,
  )

  protected def isOnboarded(
      svcStore: SvDsoStore
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] = for {
    dsoRules <- svcStore.lookupDsoRules()
    isInDsoRulesMembers = dsoRules.exists(
      _.payload.svs.keySet.contains(svcStore.key.svParty.toProtoPrimitive)
    )
    isMemberOfDecentralizedNamespace <-
      if (isInDsoRulesMembers) {
        participantAdminConnection
          .getDecentralizedNamespaceDefinition(
            dsoRules
              .map(_.domain)
              .getOrElse(
                throw Status.NOT_FOUND
                  .withDescription("Domain not found in DsoRules")
                  .asRuntimeException()
              ),
            svcStore.key.dsoParty.uid.namespace,
          )
          .map(_.mapping.owners.contains(svcStore.key.svParty.uid.namespace))
      } else {
        Future.successful(false)
      }
  } yield isInDsoRulesMembers && isMemberOfDecentralizedNamespace

}
