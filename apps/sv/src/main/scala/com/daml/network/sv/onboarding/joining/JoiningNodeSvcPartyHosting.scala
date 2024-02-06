package com.daml.network.sv.onboarding.joining

import org.apache.pekko.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.apache.pekko.stream.Materializer
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, RetryProvider}
import com.daml.network.sv.admin.api.client.SvConnection
import com.daml.network.sv.admin.api.client.commands.HttpSvAppClient.OnboardSvPartyMigrationAuthorizeProposalNotFound
import com.daml.network.sv.config.{SvAppClientConfig, SvOnboardingConfig}
import com.daml.network.sv.onboarding.SvcPartyHosting
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
            "Onboard to SVC party hosting and decentralized namespace membership",
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
                            proposalNotFound.partyToParticipantMappingSerial < partyToParticipantProposal.base.serial
                          ) {
                            // We can just retry in this case without resubmitting the proposal, the sponsor will eventually catch up
                            // and our proposal will either be valid or fail with an invalid error.
                            Future.failed(
                              Status.FAILED_PRECONDITION
                                .withDescription(
                                  s"Sponsor failed with missing proposal for serial ${proposalNotFound.partyToParticipantMappingSerial} which is smaller than our proposal for serial ${partyToParticipantProposal.base.serial}, sponsor is likely lagging behind."
                                )
                                .asRuntimeException()
                            )
                          } else {
                            Future.failed(proposalNotFound)
                          }
                        case Right(acsSnapshot) => Future.successful(acsSnapshot)
                      },
                    logger,
                  )
                  .recoverWith {
                    case proposalNotFound: OnboardSvPartyMigrationAuthorizeProposalNotFound =>
                      // Reconnect so that the participant gets its state in sync before the next retry
                      logger.info(
                        "Reconnecting to all the domains so that the proposal can be recreated from the latest base."
                      )
                      for {
                        _ <- participantAdminConnection.reconnectAllDomains()
                        _ <- retryProvider.waitUntil(
                          RetryFor.WaitingOnInitDependency,
                          s"Serial ${proposalNotFound.partyToParticipantMappingSerial} expected by sponsor is observed",
                          participantAdminConnection
                            .getPartyToParticipant(
                              domainId,
                              svcParty,
                            )
                            .map(result =>
                              if (
                                result.base.serial < proposalNotFound.partyToParticipantMappingSerial
                              ) {
                                throw Status.FAILED_PRECONDITION
                                  .withDescription(
                                    s"Current serial is ${result.base.serial}, waiting for ${proposalNotFound.partyToParticipantMappingSerial}"
                                  )
                                  .asRuntimeException()
                              }
                            ),
                          logger,
                        )
                      } yield throw Status.FAILED_PRECONDITION
                        .withDescription(
                          s"Failed because serial advanced and invalidated our proposal (serial reported by sponsor: ${proposalNotFound.partyToParticipantMappingSerial})"
                        )
                        .asRuntimeException()
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
