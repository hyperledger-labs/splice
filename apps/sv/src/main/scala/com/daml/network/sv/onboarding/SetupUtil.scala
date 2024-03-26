package com.daml.network.sv.onboarding

import com.daml.network.environment.{
  BaseLedgerConnection,
  CNLedgerConnection,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.daml.network.scan.admin.api.client.SingleScanConnection
import com.daml.network.setup.ParticipantPartyMigrator
import com.daml.network.sv.config.{MigrateSvPartyConfig, SvAppBackendConfig}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}

private[onboarding] object SetupUtil {

  def setupSvParty(
      connection: BaseLedgerConnection,
      config: SvAppBackendConfig,
      participantAdminConnection: ParticipantAdminConnection,
      clock: Clock,
      retryProvider: RetryProvider,
      loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      traceContext: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ): Future[PartyId] = {
    val initializer = new SvPartyInitializer(
      connection,
      config,
      participantAdminConnection,
      clock,
      retryProvider,
      loggerFactory,
    )
    initializer.initialize()
  }

  private final class SvPartyInitializer(
      connection: BaseLedgerConnection,
      config: SvAppBackendConfig,
      participantAdminConnection: ParticipantAdminConnection,
      clock: Clock,
      retryProvider: RetryProvider,
      override val loggerFactory: NamedLoggerFactory,
  )(implicit
      ec: ExecutionContextExecutor,
      traceContext: TraceContext,
      mat: Materializer,
      httpClient: HttpRequest => Future[HttpResponse],
      templateDecoder: TemplateJsonDecoder,
  ) extends NamedLogging {

    def initialize(
    ): Future[PartyId] = {
      val partyHint = config.svPartyHint.getOrElse(
        config.onboarding
          .getOrElse(
            sys.error("Cannot setup SV party without either party hint or an onboarding config")
          )
          .name
      )
      config.migrateSvParty match {
        case Some(MigrateSvPartyConfig(scanConfig)) =>
          val participantPartyMigrator = new ParticipantPartyMigrator(
            connection,
            participantAdminConnection,
            config.domains.global.alias,
            retryProvider,
            loggerFactory,
          )
          participantPartyMigrator.migrate(
            partyHint,
            config.ledgerApiUser,
            config.domains.global.alias,
            partyId =>
              SingleScanConnection.withSingleScanConnection(
                scanConfig,
                clock,
                retryProvider,
                loggerFactory,
              ) { scanConnection =>
                scanConnection.getAcsSnapshot(partyId)
              },
            Seq(DarResources.cantonAmulet.bootstrap, DarResources.dsoGovernance.bootstrap),
          )
        case None =>
          connection.ensureUserPrimaryPartyIsAllocated(
            config.ledgerApiUser,
            partyHint,
            participantAdminConnection,
          )
      }
    }
  }

  def ensureDsoPartyMetadataAnnotation(
      connection: CNLedgerConnection,
      config: SvAppBackendConfig,
      dsoParty: PartyId,
  )(implicit ec: ExecutionContext, traceContext: TraceContext): Future[Unit] =
    connection.ensureUserMetadataAnnotation(
      config.ledgerApiUser,
      BaseLedgerConnection.DSO_PARTY_USER_METADATA_KEY,
      dsoParty.toProtoPrimitive,
      RetryFor.WaitingOnInitDependency,
    )

  def grantSvUserRightReadAsDso(
      connection: CNLedgerConnection,
      user: String,
      dso: PartyId,
  ): Future[Unit] = {
    connection.grantUserRights(
      user,
      Seq.empty,
      Seq(dso),
    )
  }
}
