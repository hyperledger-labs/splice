package com.daml.network.sv

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.config.CNHttpClientConfig.*
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.config.{RemoteSvAppConfig, SvOnboardingConfig}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.data.ListPartyToParticipantResult
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.topology.transaction.{
  ParticipantPermission,
  RequestSide,
  TopologyChangeOp,
}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class used to orchester the flow of SVC Party hosting on SV dedicated participant.
  */
class SvcPartyHosting(
    onboardingConfig: SvOnboardingConfig,
    participantAdminConnection: ParticipantAdminConnection,
    svcParty: PartyId,
    coinAppParameters: SharedCNNodeAppParameters,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends NamedLogging {

  def svcPartyIsAuthorized(
      domainId: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Boolean] = for {
    mappings <- listActiveSvcPartyMappings(domainId, participantId, None)
  } yield {
    logger.info("SVC party mappings to our participant: " + mappings.map(_.item))
    mappings.exists(_.item.side == RequestSide.Both) || (mappings.exists(
      _.item.side == RequestSide.To
    ) && mappings.exists(_.item.side == RequestSide.From))
  }

  def start(domainId: DomainId, participantId: ParticipantId)(implicit
      traceContext: TraceContext
  ): Future[Either[String, Unit]] = {
    getSponsorSvConfig(onboardingConfig) match {
      case Some(sponsorSvConfig) =>
        for {
          _ <- participantAdminConnection.reconnectAllDomains()
          _ <- authorizeSvcPartyToParticipant(
            domainId,
            participantId,
            RequestSide.To,
          )
          _ <- participantAdminConnection.disconnectFromAllDomains()
          _ = logger.info("candidate SV participant disconnected from global domain")
          acsBytes <- getAuthorizationAndAcsFromSponsor(sponsorSvConfig, participantId)
          _ <- participantAdminConnection.uploadAcsSnapshot(acsBytes)
          _ = logger.info(
            "Imported Acs snapshot from sponsor SV participant to candidate participant"
          )
          _ <- participantAdminConnection.reconnectAllDomains()
          _ = logger.info("candidate SV participant reconnected to global domain")
          _ <- waitForSvcPartyToParticipantAuthorization(domainId, participantId, RequestSide.From)
          _ = logger.info(s"svc party is now hosted in the candidate SV participant $participantId")
        } yield Right(())
      case None =>
        Future.successful(Left("unexpected on-boarding config"))
    }
  }

  private def getSponsorSvConfig(
      onboardingConfig: SvOnboardingConfig
  ): Option[RemoteSvAppConfig] =
    onboardingConfig match {
      case SvOnboardingConfig.JoinWithKey(_, sponsorSv, _, _) =>
        Some(sponsorSv)
      case _ => None
    }

  private def getAuthorizationAndAcsFromSponsor(
      sponsorSvConfig: RemoteSvAppConfig,
      candidateParticipantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[ByteString] = {
    logger.info(
      s"Requesting to authorize SVC party hosting via SV at: ${sponsorSvConfig.adminApi.url}"
    )
    retryProvider.retryForAutomation(
      "authorize SVC party hosting",
      SvConnection(
        sponsorSvConfig.adminApi,
        retryProvider,
        coinAppParameters.processingTimeouts,
        loggerFactory,
      ).flatMap { svConnection =>
        svConnection
          .authorizeSvcPartyHosting(candidateParticipantId)
          .map(bs => ByteString.copyFrom(bs.asByteBuffer))
          .andThen(_ => svConnection.close())
      },
      logger,
    )
  }

  def listActiveSvcPartyMappings(
      domain: DomainId,
      participantId: ParticipantId,
      side: Option[RequestSide],
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResult]] =
    participantAdminConnection
      .listPartyToParticipantMappings(
        filterStore = domain.toProtoPrimitive,
        operation = Some(TopologyChangeOp.Add),
        filterParty = svcParty.toProtoPrimitive,
        filterParticipant = participantId.uid.toProtoPrimitive,
        filterRequestSide = side,
      )
      .map(_.filter(_.item.permission.isActive))

  def authorizeSvcPartyToParticipant(
      domain: DomainId,
      participantId: ParticipantId,
      side: RequestSide,
  )(implicit traceContext: TraceContext): Future[Instant] =
    // check if svc party has already been authorized to be hosted by the participant
    listActiveSvcPartyMappings(domain, participantId, Some(side)).flatMap {
      case Seq() =>
        for {
          _ <- participantAdminConnection
            .authorizePartyToParticipant(
              TopologyChangeOp.Add,
              svcParty,
              participantId,
              side,
              ParticipantPermission.Observation,
            )
          authorizedAt <- waitForSvcPartyToParticipantAuthorization(domain, participantId, side)
        } yield authorizedAt
      case Seq(mapping) =>
        logger.info(
          s"Party ${svcParty.toProtoPrimitive} already authorized to participant ${participantId.toProtoPrimitive} from side $side"
        )
        Future.successful(mapping.context.validFrom)
      case _ =>
        Future.failed(
          new IllegalStateException(
            "More than 1 svc party to participant mapping which is not expected"
          )
        )
    }

  // Wait for party to participant authorization to be reflected from the TopologyAdminCommand.ListPartyToParticipant
  // It is used in both candidate and sponsor side to ensure the party to participant are added successfully.
  // It returns the timestamp when the authorization becomes valid.
  private def waitForSvcPartyToParticipantAuthorization(
      domain: DomainId,
      participantId: ParticipantId,
      side: RequestSide,
  )(implicit traceContext: TraceContext): Future[Instant] = retryProvider.retryForClientCalls(
    "wait for svc party to participant authorization to complete", {
      listActiveSvcPartyMappings(domain, participantId, Some(side)).map {
        case Seq(mapping) =>
          logger.debug(
            s"the party to participant authorization $mapping has been observed. done waiting."
          )
          mapping.context.validFrom
        case Seq() =>
          throw new StatusRuntimeException(
            Status.NOT_FOUND.withDescription(
              s"Authorization to $participantId on $side is still in progress"
            )
          )
        case _ =>
          throw new StatusRuntimeException(
            Status.INTERNAL.withDescription("Unexpected number of mappings")
          )
      }
    },
    logger,
  )
}
