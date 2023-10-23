package com.daml.network.sv.setup

import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.Materializer
import cats.data.OptionT
import com.daml.network.environment.TopologyAdminConnection.TopologyResult
import com.daml.network.environment.{ParticipantAdminConnection, RetryProvider}
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.OnboardSvPartyMigrationAuthorizeProposalNotFound
import com.daml.network.sv.config.{SvAppClientConfig, SvOnboardingConfig}
import com.daml.network.sv.setup.SvcPartyHosting.PartyToParticipantProposalThresholdMismatch
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.Fingerprint
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
          response <- retryProvider.retryForAutomation(
            "Onboard to svc party hosting and unionspace membership",
            SvConnection(
              sponsorSvConfig.adminApi,
              retryProvider,
              loggerFactory,
            ).flatMap { svConnection =>
              logger.info(s"Proposing party allocation to participant $participantId")
              (for {
                svcInfo <- svConnection.getSvcInfo()
                svcMembersSize = svcInfo.svcRules.payload.members.size()
                partyToParticipantProposal <- participantAdminConnection
                  .ensurePartyToParticipantProposal(
                    domainId,
                    svcParty,
                    participantId,
                    svcMembersSize,
                    svParty.uid.namespace.fingerprint,
                  )
                _ = logger.info("Proposing addition to unionspace.")
                unionspaceDefinitionProposal <- participantAdminConnection
                  .ensureUnionspaceDefinitionProposal(
                    domainId,
                    svcParty.uid.namespace,
                    svParty.uid.namespace,
                    svParty.uid.namespace.fingerprint,
                  )
                _ = logger.info("Disconnecting from all domains")
                _ <- participantAdminConnection.disconnectFromAllDomains()
                _ = logger.info("candidate SV participant disconnected from global domain")
                response <- retryProvider
                  .retryForAutomation(
                    "authorize svc party hosting on sponsor",
                    svConnection
                      .authorizeSvcPartyHosting(
                        participantId,
                        svParty,
                      )
                      .flatMap {
                        case Left(proposalNotFound) =>
                          if (
                            proposalNotFound.partyToParticipantMappingSerial >= partyToParticipantProposal.base.serial || proposalNotFound.unionspaceDefinitionSerial >= unionspaceDefinitionProposal.base.serial
                          ) {
                            logger.info(
                              s"Proposals $partyToParticipantProposal, $unionspaceDefinitionProposal no longer matches base serial, must be re-created $proposalNotFound"
                            )
                            Future.failed(proposalNotFound)
                          } else {
                            svConnection.getSvcInfo().flatMap { newSvcInfo =>
                              if (
                                newSvcInfo.svcRules.payload.members
                                  .size() != svcMembersSize
                              ) {
                                Future.failed(PartyToParticipantProposalThresholdMismatch())
                              } else {
                                Future.failed(
                                  Status.NOT_FOUND
                                    .withDescription(
                                      s"Proposal not found by sponsor $proposalNotFound but serial matches proposal serial $partyToParticipantProposal"
                                    )
                                    .asRuntimeException()
                                )
                              }
                            }
                          }
                        case Right(acsSnapshot) => Future.successful(acsSnapshot)
                      },
                    logger,
                  )
                  .recoverWith {
                    case _: PartyToParticipantProposalThresholdMismatch =>
                      participantAdminConnection
                        .reconnectAllDomains()
                        .map(_ =>
                          throw Status.FAILED_PRECONDITION
                            .withDescription(
                              s"The size of the SVC has changed during the party migration, the proposal must be recreated."
                            )
                            .asRuntimeException()
                        )
                    case proposalNotFound: OnboardSvPartyMigrationAuthorizeProposalNotFound =>
                      // Reconnect so that the participant gets its state in sync before the next retry
                      logger.info(
                        "Reconnecting to all the domains so that the proposal can be recreated from the latest base."
                      )
                      participantAdminConnection
                        .reconnectAllDomains()
                        .map(_ =>
                          throw Status.FAILED_PRECONDITION
                            .withDescription(
                              s"Proposal not found by sponsor, and serial $proposalNotFound does not match proposal serial ${partyToParticipantProposal.base.serial}, ${unionspaceDefinitionProposal.base.serial}. Proposal must be re-created."
                            )
                            .asRuntimeException()
                        )
                  }
              } yield {
                response
              }).andThen(_ => svConnection.close())
            },
            logger,
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

  private def listActiveSvcPartyMappings(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Seq[TopologyResult[PartyToParticipantX]]] =
    listActivePartyToParticipantMappings(svcParty, domain, Some(participantId))

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
      svcSize: Int,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): Future[Instant] =
    for {
      _ <- participantAdminConnection.ensurePartyToParticipant(
        domain,
        svcParty,
        participantId,
        signedBy,
        svcSize,
      )
      authorizedAt <- waitForSvcPartyToParticipantAuthorization(domain, participantId)
    } yield {
      logger.info(
        s"Party $svcParty is authorized on participant $participantId"
      )
      authorizedAt
    }

  // Wait for party to participant authorization to be reflected from the TopologyAdminCommand.ListPartyToParticipant
  // It is used in both candidate and sponsor side to ensure the party to participant are added successfully.
  // It returns the timestamp when the authorization becomes valid.
  private def waitForSvcPartyToParticipantAuthorization(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): Future[Instant] = retryProvider.retryForClientCalls(
    "wait for svc party to participant authorization to complete",
    getSvcPartyToParticipantTransaction(domain, participantId).foldF(
      Future.failed(
        Status.NOT_FOUND
          .withDescription(
            show"Authorization to $participantId is still in progress"
          )
          .asRuntimeException()
      )
    ) { mapping =>
      val validFrom = mapping.base.validFrom
      logger.debug(show"the party to participant authorization $mapping has been observed")
      participantAdminConnection
        .waitForTopologyChangeToBeValid(
          show"SVC party to participant mapping to $participantId",
          validFrom,
        )
        .map(_ => validFrom)
    },
    logger,
  )

  /** Return the transaction that first added the participant to PartyToParticipant
    * if the participant is still included in the latest state.
    */
  private def getSvcPartyToParticipantTransaction(
      domain: DomainId,
      participantId: ParticipantId,
  )(implicit traceContext: TraceContext): OptionT[Future, TopologyResult[PartyToParticipantX]] =
    OptionT(for {
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
    })

}

object SvcPartyHosting {

  sealed trait SvcPartyMigrationFailure

  final case class LockAcquireFailure(lockReason: String, cause: Throwable)
      extends SvcPartyMigrationFailure
  final case class RequiredProposalsNotFound(
      partoToParticipantSerial: PositiveInt,
      unionspaceDefinitionSerial: PositiveInt,
  ) extends SvcPartyMigrationFailure

  case class PartyToParticipantProposalThresholdMismatch()
      extends RuntimeException("Proposal must be recreated because the threshold has changed.")
}
