package com.daml.network.sv

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.syntax.foldable.*
import cats.syntax.traverse.*
import com.daml.network.config.SharedCNNodeAppParameters
import com.daml.network.environment.{
  ParticipantAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient
import com.daml.network.sv.config.{SvAppClientConfig, SvOnboardingConfig}
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.admin.api.client.data.ListPartyToParticipantResult
import com.digitalasset.canton.admin.api.client.data.topologyx.{
  ListPartyToParticipantResult as ListPartyToParticipantResultX
}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.transaction.{
  ParticipantPermission,
  RequestSide,
  TopologyChangeOp,
  TopologyChangeOpX,
}
import com.digitalasset.canton.topology.transaction.SignedTopologyTransactionX.GenericSignedTopologyTransactionX
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

/** Class used to orchester the flow of SVC Party hosting on SV dedicated participant.
  */
class SvcPartyHosting(
    onboardingConfig: SvOnboardingConfig,
    participantAdminConnection: ParticipantAdminConnection,
    sequencerAdminConnection: Option[SequencerAdminConnection],
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

  private val useXNodes = sequencerAdminConnection.isDefined

  def svcPartyIsAuthorized(
      domainId: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    if (useXNodes) {
      for {
        mappings <- listActiveSvcPartyMappingsX(domainId, participantId)
      } yield {
        logger.info("SVC party mappings to our participant: " + mappings.map(_.item))
        mappings.nonEmpty
      }
    } else {
      for {
        mappings <- listActiveSvcPartyMappings(domainId, participantId, None)
      } yield {
        logger.info("SVC party mappings to our participant: " + mappings.map(_.item))
        mappings.exists(_.item.side == RequestSide.Both) || (mappings.exists(
          _.item.side == RequestSide.To
        ) && mappings.exists(_.item.side == RequestSide.From))
      }
    }

  def start(domainId: DomainId, participantId: ParticipantId, svParty: PartyId)(implicit
      traceContext: TraceContext
  ): Future[Either[String, Unit]] = {
    getSponsorSvConfig(onboardingConfig) match {
      case Some(sponsorSvConfig) =>
        for {
          _ <- participantAdminConnection.reconnectAllDomains()
          _ <-
            if (useXNodes) {
              // At the moment, Canton accepts the transaction as soon as anyone submits it. Eventually,
              // the transaction will have to be submitted by the target participant (as part of this flow likely)
              // and by a consensus of SVs (probably as part of a reconciliation loop)
              logger.info(
                s"Not submitting party to participant transaction on target participant on X nodes"
              )
              Future.unit
            } else {
              authorizeSvcPartyToParticipant(
                domainId,
                participantId,
                RequestSide.To,
              ).map(_ => ())
            }
          _ <- participantAdminConnection.disconnectFromAllDomains()
          sequencerIdentity <- sequencerAdminConnection.traverse { connection =>
            connection.getSequencerId.flatMap(id =>
              connection.getSequencerIdentityTransactions(id).map((id, _))
            )
          }
          _ = logger.info("candidate SV participant disconnected from global domain")
          response <- getAuthorizationAndAcsFromSponsor(
            sponsorSvConfig,
            participantId,
            sequencerIdentity,
            svParty,
          )
          _ <- participantAdminConnection.uploadAcsSnapshot(response.acsSnapshot)
          _ = logger.info(
            "Imported Acs snapshot from sponsor SV participant to candidate participant"
          )
          _ <- participantAdminConnection.reconnectAllDomains()
          _ = logger.info("candidate SV participant reconnected to global domain")
          _ <- waitForSvcPartyToParticipantAuthorization(domainId, participantId, RequestSide.From)
          _ = logger.info(s"svc party is now hosted in the candidate SV participant $participantId")
          conAndSnapshot = for {
            con <- sequencerAdminConnection
            snapshot <- response.sequencerSnapshot
          } yield (con, snapshot)
          _ <- conAndSnapshot.traverse_ { case (con, snapshot) =>
            logger.info(s"Bootstrapping sequencer from snapshot")
            con
              .assignFromSnapshot(
                snapshot.topologySnapshot,
                snapshot.staticDomainParameters,
                snapshot.sequencerSnapshot,
              )
              .map { _ =>
                logger.info(s"Sequencer bootstrapping complete")
              // TODO(#5096) Connect our participant to the sequencer
              }
          }
        } yield Right(())
      case None =>
        Future.successful(Left("unexpected on-boarding config"))
    }
  }

  private def getSponsorSvConfig(
      onboardingConfig: SvOnboardingConfig
  ): Option[SvAppClientConfig] =
    onboardingConfig match {
      case SvOnboardingConfig.JoinWithKey(_, sponsorSv, _, _) =>
        Some(sponsorSv)
      case _ => None
    }

  private def getAuthorizationAndAcsFromSponsor(
      sponsorSvConfig: SvAppClientConfig,
      candidateParticipantId: ParticipantId,
      candidateSequencerIdentity: Option[(SequencerId, Seq[GenericSignedTopologyTransactionX])],
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
        coinAppParameters.processingTimeouts,
        loggerFactory,
      ).flatMap { svConnection =>
        svConnection
          .authorizeSvcPartyHosting(candidateParticipantId, candidateSequencerIdentity, svParty)
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
    listActivePartyToParticipantMappings(svcParty, domain, participantId, side)

  def listActivePartyToParticipantMappings(
      party: PartyId,
      domain: DomainId,
      participantId: ParticipantId,
      side: Option[RequestSide] = None,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResult]] =
    participantAdminConnection
      .listPartyToParticipantMappings(
        filterStore = domain.toProtoPrimitive,
        operation = Some(TopologyChangeOp.Add),
        filterParty = party.toProtoPrimitive,
        filterParticipant = participantId.uid.toProtoPrimitive,
        filterRequestSide = side,
      )

  def listActiveSvcPartyMappingsX(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResultX]] =
    listActivePartyToParticipantMappingsX(svcParty, domain, participantId)

  def listActivePartyToParticipantMappingsX(
      party: PartyId,
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Seq[ListPartyToParticipantResultX]] =
    participantAdminConnection
      .listPartyToParticipantMappingsX(
        filterStore = domain.toProtoPrimitive,
        operation = Some(TopologyChangeOpX.Replace),
        filterParticipant = participantId.toProtoPrimitive,
        filterParty = party.toProtoPrimitive,
      )

  def isPartyHostedOnTargetParticipant(
      party: PartyId,
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Boolean] =
    if (useXNodes) {
      listActivePartyToParticipantMappingsX(party, domain, participantId).map(_.nonEmpty)
    } else {
      listActivePartyToParticipantMappings(party, domain, participantId).map(_.nonEmpty)
    }

  def authorizeSvcPartyToParticipant(
      domain: DomainId,
      participantId: ParticipantId,
      side: RequestSide,
  )(implicit traceContext: TraceContext): Future[Instant] =
    if (useXNodes) {
      // check if svc party has already been authorized to be hosted by the participant
      listActiveSvcPartyMappingsX(domain, participantId).flatMap {
        case Seq() =>
          for {
            sourceParticipant <- participantAdminConnection.getParticipantId(useXNodes)
            // Get the existing mapping
            mappings <- listActiveSvcPartyMappingsX(domain, sourceParticipant)
            mapping = mappings match {
              case Seq(mapping) => mapping
              case _ => throw new IllegalStateException(s"Mappings are borked: $mappings")
            }
            // This is run under a (SvcRules) lock so no need to worry about race conditions with concurrent onboardings.
            _ <- participantAdminConnection.authorizePartyToParticipantX(
              svcParty,
              mapping.item.participants.map(_.participantId),
              participantId,
              sourceParticipant,
            )
            authorizedAt <- waitForSvcPartyToParticipantAuthorization(
              domain,
              participantId,
              side,
            )
          } yield {
            authorizedAt
          }
        case Seq(mapping) =>
          logger.info(
            s"Party ${svcParty.toProtoPrimitive} already authorized to participant ${participantId.toProtoPrimitive}"
          )
          Future.successful(mapping.context.validFrom)
        case _ =>
          Future.failed(
            new IllegalStateException(
              "More than 1 svc party to participant mapping which is not expected"
            )
          )
      }
    } else {
      // check if svc party has already been authorized to be hosted by the participant
      listActiveSvcPartyMappings(domain, participantId, Some(side)).flatMap {
        case Seq() =>
          for {
            _ <- participantAdminConnection.authorizePartyToParticipant(
              TopologyChangeOp.Add,
              svcParty,
              participantId,
              side,
              ParticipantPermission.Observation,
            )
            authorizedAt <- waitForSvcPartyToParticipantAuthorization(
              domain,
              participantId,
              side,
            )
          } yield {
            authorizedAt
          }
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
      if (useXNodes) {
        listActiveSvcPartyMappingsX(domain, participantId).map {
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
      } else {
        listActiveSvcPartyMappings(domain, participantId, Some(side)).map {
          case Seq(mapping) =>
            logger.debug(
              s"the party to participant authorization $mapping has been observed. done waiting."
            )
            mapping.context.validFrom
          case Seq() =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(
                s"Authorization to $participantId is still in progress"
              )
            )
          case _ =>
            throw new StatusRuntimeException(
              Status.INTERNAL.withDescription("Unexpected number of mappings")
            )
        }
      }
    },
    logger,
  )
}
