package com.daml.network.sv.onboarding

import com.daml.network.environment.RetryProvider
import com.daml.network.scan.admin.api.client.{ScanConnection, SingleScanConnection}
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.sv.config.MigrateSvPartyConfig
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.topology.transaction.*
import cats.data.EitherT
import com.daml.network.environment.{
  BaseLedgerConnection,
  CNLedgerConnection,
  DarResources,
  ParticipantAdminConnection,
  RetryFor,
}
import com.daml.network.sv.config.SvAppBackendConfig
import com.daml.network.util.{TemplateJsonDecoder, UploadablePackage}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.logging.{NamedLogging, NamedLoggerFactory}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{Identifier, ParticipantId, PartyId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
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
        case Some(MigrateSvPartyConfig(scanConfig)) => {
          for {
            participantId <- participantAdminConnection.getParticipantId()
            partyId = PartyId(
              UniqueIdentifier(Identifier.tryCreate(partyHint), participantId.uid.namespace)
            )
            // There isn't a great way to check if we already imported the ACS so instead we check if the user already has a primary party
            // which is set afterwards. If things really go wrong during this step, we can always start over on a fresh participnat.
            primaryPartyO <- connection.getOptionalPrimaryParty(config.ledgerApiUser)
            _ <- primaryPartyO match {
              case Some(_) =>
                logger.info("Party migration already complete, continuing")
                Future.unit
              case None =>
                logger.info(s"Migrating SV party $partyId to new participant")
                for {
                  _ <- migrateSvParty(
                    config.domains.global.alias,
                    partyId,
                    participantId,
                    scanConfig,
                  )
                  _ <- connection.ensureUserHasPrimaryParty(config.ledgerApiUser, partyId)
                } yield partyId
            }
          } yield partyId
        }
        case None =>
          connection.ensureUserPrimaryPartyIsAllocated(
            config.ledgerApiUser,
            partyHint,
            participantAdminConnection,
          )
      }
    }

    private def migrateSvParty(
        domainAlias: DomainAlias,
        partyId: PartyId,
        participantId: ParticipantId,
        scanConfig: ScanAppClientConfig,
    ): Future[PartyId] = {
      for {
        domainId <- participantAdminConnection.getDomainId(domainAlias)
        _ <- participantAdminConnection.ensureTopologyMapping[PartyToParticipantX](
          store = TopologyStoreId.DomainStore(domainId),
          s"SV party $partyId is hosted on participant $participantId",
          EitherT {
            participantAdminConnection.getPartyToParticipant(domainId, partyId).flatMap { result =>
              result.mapping.participants match {
                case Seq(participant) if participant.participantId != participantId =>
                  Future.successful(Left(result))
                case Seq(participant) if participant.participantId == participantId =>
                  Future.successful(Right(result))
                case participants =>
                  Future.failed(
                    Status.INTERNAL
                      .withDescription(
                        s"SV party $partyId is hosted on multiple participant, giving up: $participants"
                      )
                      .asRuntimeException()
                  )
              }
            }
          },
          _ =>
            Right(
              PartyToParticipantX(
                partyId = partyId,
                domainId = None,
                threshold = PositiveInt.one,
                participants =
                  Seq(HostingParticipant(participantId, ParticipantPermissionX.Submission)),
                groupAddressing = false,
              )
            ),
          signedBy = participantId.uid.namespace.fingerprint,
          retryFor = RetryFor.WaitingOnInitDependency,
        )
        _ = logger.info(s"Importing ACS for SV party $partyId from scan")
        _ <- importAcs(partyId, scanConfig)
      } yield partyId
    }

    private def importAcs(
        partyId: PartyId,
        scanConfig: ScanAppClientConfig,
    ): Future[Unit] =
      for {
        _ <- participantAdminConnection.uploadDarFiles(
          Seq(DarResources.cantonCoin.bootstrap, DarResources.svcGovernance.bootstrap)
            .map(UploadablePackage.fromResource(_)),
          RetryFor.WaitingOnInitDependency,
        )
        _ <- participantAdminConnection.disconnectFromAllDomains()
        acsSnapshot <- withScanConnection(scanConfig, clock, retryProvider, loggerFactory) {
          scanConnection =>
            scanConnection.getAcsSnapshot(partyId)
        }
        _ <- participantAdminConnection.uploadAcsSnapshot(acsSnapshot)
        _ <- participantAdminConnection.reconnectAllDomains()
        _ = logger.info("ACS import complete")
      } yield ()

    private def withScanConnection[T](
        scanConfig: ScanAppClientConfig,
        clock: Clock,
        retryProvider: RetryProvider,
        loggerFactory: NamedLoggerFactory,
    )(f: SingleScanConnection => Future[T])(implicit
        ec: ExecutionContextExecutor,
        traceContext: TraceContext,
        mat: Materializer,
        httpClient: HttpRequest => Future[HttpResponse],
        templateDecoder: TemplateJsonDecoder,
    ) =
      for {
        scanConnection <- ScanConnection.singleUncached(
          scanConfig,
          clock,
          retryProvider,
          loggerFactory,
        )
        r <- f(scanConnection).andThen { _ => scanConnection.close() }
      } yield r
  }

  def ensureSvcPartyMetadataAnnotation(
      connection: CNLedgerConnection,
      config: SvAppBackendConfig,
      svcParty: PartyId,
  )(implicit ec: ExecutionContext): Future[Unit] = connection.ensureUserMetadataAnnotation(
    config.ledgerApiUser,
    BaseLedgerConnection.SVC_PARTY_USER_METADATA_KEY,
    svcParty.toProtoPrimitive,
    RetryFor.WaitingOnInitDependency,
  )

  def grantSvUserRightReadAsSvc(
      connection: CNLedgerConnection,
      user: String,
      svc: PartyId,
  ): Future[Unit] = {
    connection.grantUserRights(
      user,
      Seq.empty,
      Seq(svc),
    )
  }
}
