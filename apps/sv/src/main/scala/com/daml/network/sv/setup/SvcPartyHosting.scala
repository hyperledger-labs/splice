package com.daml.network.sv.setup

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.environment.TopologyAdminConnection.TopologyResult
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.sv.config.{SvAppClientConfig, SvOnboardingConfig}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.store.TimeQueryX
import com.digitalasset.canton.topology.transaction.{PartyToParticipantX, TopologyChangeOpX}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import io.grpc.Status

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class used to orchestrate the flow of SVC Party hosting on SV dedicated participant.
  */
class SvcPartyHosting(
    onboardingConfig: Option[SvOnboardingConfig],
    participantAdminConnection: ParticipantAdminConnection,
    svcParty: PartyId,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends NamedLogging {

  def isSvcPartyAuthorizedOn(
      domainId: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      mappings <- listActiveSvcPartyMappings(domainId, participantId)
    } yield {
      logger.info("SVC party mappings to our participant: " + mappings.map(_.mapping))
      mappings.nonEmpty
    }

  def start(domainId: DomainId, participantId: ParticipantId, svParty: PartyId)(implicit
      traceContext: TraceContext
  ): Future[Either[String, Unit]] = {
    getSponsorSvConfig(onboardingConfig) match {
      case Some(sponsorSvConfig) =>
        for {
          _ <- participantAdminConnection.reconnectAllDomains()
          // At the moment, Canton accepts the transaction as soon as anyone submits it. Eventually,
          // the transaction will have to be submitted by the candidate SV’s participant (as part of this flow likely)
          // and by a consensus of SVs (probably as part of a reconciliation loop).
          _ = logger.info("Disconnecting from all domains")
          _ <- participantAdminConnection.disconnectFromAllDomains()
          _ = logger.info("candidate SV participant disconnected from global domain")
          response <- getAuthorizationAndAcsFromSponsor(
            sponsorSvConfig,
            participantId,
            svParty,
          )
          _ <- participantAdminConnection.uploadAcsSnapshot(response.acsSnapshot)
          _ = logger.info(
            "Imported Acs snapshot from sponsor SV participant to candidate participant"
          )
          _ <- participantAdminConnection.reconnectAllDomains()
          _ = logger.info("candidate SV participant reconnected to global domain")
          _ <- waitForSvcPartyToParticipantAuthorization(
            domainId,
            participantId,
          )
          _ = logger.info(
            s"svc party is now hosted in the candidate SV participant $participantId"
          )
        } yield Right(())
      case None =>
        Future.successful(Left("unexpected onboarding config"))
    }
  }

  private def getSponsorSvConfig(
      onboardingConfig: Option[SvOnboardingConfig]
  ): Option[SvAppClientConfig] =
    onboardingConfig match {
      case Some(SvOnboardingConfig.JoinWithKey(_, sponsorSv, _, _)) =>
        Some(sponsorSv)
      case _ => None
    }

  private def getAuthorizationAndAcsFromSponsor(
      sponsorSvConfig: SvAppClientConfig,
      candidateParticipantId: ParticipantId,
      svParty: PartyId,
  )(implicit
      traceContext: TraceContext
  ): Future[HttpSvAppClient.OnboardSvPartyMigrationAuthorizeResponse] = {
    logger.info(
      s"Requesting to authorize SVC party hosting via SV at: ${sponsorSvConfig.adminApi.url}"
    )
    retryProvider.retryForAutomation(
      "authorize SVC party hosting",
      SvConnection(
        sponsorSvConfig.adminApi,
        retryProvider,
        loggerFactory,
      ).flatMap { svConnection =>
        svConnection
          .authorizeSvcPartyHosting(
            candidateParticipantId,
            svParty,
          )
          .andThen(_ => svConnection.close())
      },
      logger,
    )
  }

  private def listActiveSvcPartyMappings(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipantX]]] =
    listActivePartyToParticipantMappings(svcParty, domain, Some(participantId))

  /** Return the transaction that first added the participant to PartyToParticipant
    * if the participant is still included in the latest state.
    */
  private def getSvcPartyToParticipantTransaction(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Option[TopologyResult[PartyToParticipantX]]] =
    for {
      // We only fetch transactions for the svc party so one per SV on/offboarding which
      // we expect to be rare so we can fetch the entire history.
      xs <- listActivePartyToParticipantMappings(
        svcParty,
        domain,
        None,
        TimeQueryX.Range(None, None),
      )
    } yield {
      // topology read service _should_ sort this but given that we assume everything
      // fits in memory we may as well go for the extra safeguard.
      xs.sortBy(_.base.serial).foldLeft[Option[TopologyResult[PartyToParticipantX]]](None) {
        // Participant is no longer hosting the party
        case (_, newMapping) if !newMapping.mapping.participantIds.contains(participantId) => None
        // Participant starts hosting party
        case (None, newMapping) if newMapping.mapping.participantIds.contains(participantId) =>
          Some(newMapping)
        // Participant is hosting party but this is not the mapping that added it.
        case (Some(mapping), newMapping)
            if newMapping.mapping.participantIds.contains(participantId) =>
          Some(mapping)
        case _ => None
      }
    }

  private def listActivePartyToParticipantMappings(
      party: PartyId,
      domain: DomainId,
      participantId: Option[ParticipantId],
      timeQuery: TimeQueryX = TimeQueryX.HeadState,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipantX]]] =
    participantAdminConnection
      .listPartyToParticipant(
        filterStore = domain.toProtoPrimitive,
        operation = Some(TopologyChangeOpX.Replace),
        filterParticipant = participantId.fold("")(_.toProtoPrimitive),
        filterParty = party.toProtoPrimitive,
        timeQuery = timeQuery,
      )

  def isPartyHostedOnTargetParticipant(
      party: PartyId,
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    listActivePartyToParticipantMappings(party, domain, Some(participantId)).map(_.nonEmpty)

  def authorizeSvcPartyToParticipant(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Instant] =
    // check if svc party has already been authorized to be hosted by the participant
    getSvcPartyToParticipantTransaction(domain, participantId).flatMap {
      case None =>
        for {
          sourceParticipant <- participantAdminConnection.getParticipantId()
          // Get the existing mapping
          mappings <- listActiveSvcPartyMappings(domain, sourceParticipant)
          mapping = mappings match {
            case Seq(mapping) => mapping
            case _ => throw new IllegalStateException(s"Mappings are borked: $mappings")
          }
          _ <- participantAdminConnection.ensurePartyToParticipant(
            domain,
            svcParty,
            participantId,
            sourceParticipant.uid.namespace.fingerprint,
          )
          authorizedAt <- waitForSvcPartyToParticipantAuthorization(
            domain,
            participantId,
          )
        } yield {
          authorizedAt
        }
      case Some(mapping) =>
        logger.info(
          s"Party ${svcParty.toProtoPrimitive} already authorized to participant ${participantId.toProtoPrimitive}"
        )
        Future.successful(mapping.base.validFrom)
    }

  // Wait for party to participant authorization to be reflected from the TopologyAdminCommand.ListPartyToParticipant
  // It is used in both candidate and sponsor side to ensure the party to participant are added successfully.
  // It returns the timestamp when the authorization becomes valid.
  private def waitForSvcPartyToParticipantAuthorization(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Instant] = retryProvider.retryForClientCalls(
    "wait for svc party to participant authorization to complete",
    listActiveSvcPartyMappings(domain, participantId).flatMap {
      case Seq(mapping) =>
        val validFrom = mapping.base.validFrom
        logger.debug(show"the party to participant authorization $mapping has been observed")
        participantAdminConnection
          .waitForTopologyChangeToBeValid(
            show"SVC party to participant mapping to $participantId",
            validFrom,
          )
          .map(_ => validFrom)
      case Seq() =>
        Future.failed(
          Status.NOT_FOUND
            .withDescription(
              show"Authorization to $participantId is still in progress"
            )
            .asRuntimeException()
        )
      case _ =>
        Future.failed(
          Status.INTERNAL.withDescription("Unexpected number of mappings").asRuntimeException()
        )
    },
    logger,
  )
}
