package com.daml.network.sv.onboarding.joining

import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, RetryProvider}
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.OnboardSvPartyMigrationAuthorizeProposalNotFound
import com.daml.network.sv.config.{SvAppClientConfig, SvOnboardingConfig}
import com.daml.network.sv.onboarding.SvcPartyHosting
import com.daml.network.sv.onboarding.SvcPartyHosting.PartyToParticipantProposalThresholdMismatch
import com.daml.network.util.TemplateJsonDecoder
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

class JoiningNodeSvcPartyHosting(
    participantAdminConnection: ParticipantAdminConnection,
    onboardingConfig: Option[SvOnboardingConfig],
    svcParty: PartyId,
    svcPartyHosting: SvcPartyHosting,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpRequest => Future[HttpResponse],
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends NamedLogging {

  def hostPartyOnOwnParticipant(domainId: DomainId, participantId: ParticipantId, svParty: PartyId)(
      implicit traceContext: TraceContext
  ): Future[Either[String, Unit]] = {
    getSponsorSvConfig(onboardingConfig) match {
      case Some(sponsorSvConfig) =>
        for {
          response <- retryProvider.retry(
            RetryFor.WaitingOnInitDependency,
            "Onboard to SVC party hosting and unionspace membership",
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
                  .ensurePartyToParticipantAdditionProposal(
                    domainId,
                    svcParty,
                    participantId,
                    svParty.uid.namespace.fingerprint,
                  )
                _ = logger.info("Disconnecting from all domains")
                _ <- participantAdminConnection.disconnectFromAllDomains()
                _ = logger.info("candidate SV participant disconnected from global domain")
                response <- retryProvider
                  .retry(
                    RetryFor.WaitingOnInitDependency,
                    "authorize SVC party hosting on sponsor",
                    svConnection
                      .authorizeSvcPartyHosting(
                        participantId,
                        svParty,
                      )
                      .flatMap {
                        case Left(proposalNotFound) =>
                          if (
                            proposalNotFound.partyToParticipantMappingSerial >= partyToParticipantProposal.base.serial
                          ) {
                            logger.info(
                              s"Proposals $partyToParticipantProposal no longer matches base serial, must be re-created $proposalNotFound"
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
                              s"Proposal not found by sponsor, and serial $proposalNotFound does not match proposal serial ${partyToParticipantProposal.base.serial}. Proposal must be re-created."
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
          _ <- svcPartyHosting.waitForSvcPartyToParticipantAuthorization(
            domainId,
            participantId,
          )
          _ = logger.info(
            s"SVC party is now hosted in the candidate SV participant $participantId"
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

}
