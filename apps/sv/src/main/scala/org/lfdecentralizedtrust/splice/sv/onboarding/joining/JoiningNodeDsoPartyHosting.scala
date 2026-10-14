// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.joining

import org.lfdecentralizedtrust.splice.config.UpgradesConfig
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologySnapshot
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.lfdecentralizedtrust.splice.sv.admin.api.client.SvConnection
import org.lfdecentralizedtrust.splice.sv.admin.api.client.commands.HttpSvPublicAppClient.OnboardSvPartyMigrationAuthorizeProposalNotFound
import org.lfdecentralizedtrust.splice.sv.config.{SvOnboardingConfig, SvOnboardingSnapshotsConfig}
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.SvAppClientConfig
import org.lfdecentralizedtrust.splice.util.TemplateJsonDecoder
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{SynchronizerId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.apache.pekko.stream.Materializer

import scala.concurrent.{ExecutionContextExecutor, Future}

class JoiningNodeDsoPartyHosting(
    participantAdminConnection: ParticipantAdminConnection,
    onboardingConfig: Option[SvOnboardingConfig],
    upgradesConfig: UpgradesConfig,
    dsoParty: PartyId,
    dsoPartyHosting: DsoPartyHosting,
    onboardingSnapshotsConfig: SvOnboardingSnapshotsConfig,
    retryProvider: RetryProvider,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    httpClient: HttpClient,
    templateDecoder: TemplateJsonDecoder,
    mat: Materializer,
) extends NamedLogging {

  def hostPartyOnOwnParticipant(
      synchronizerAlias: SynchronizerAlias,
      synchronizerId: SynchronizerId,
      participantId: ParticipantId,
      svParty: PartyId,
  )(implicit
      traceContext: TraceContext
  ): Future[Either[String, Unit]] = {
    getSponsorSvConfig(onboardingConfig) match {
      case Some(sponsorSvConfig) =>
        val snapshotDownload =
          SvOnboardingSnapshotDownload(onboardingSnapshotsConfig, "dso-party-acs", retryProvider)
        (for {
          acsSnapshot <- retryProvider.retry(
            RetryFor.WaitingOnInitDependency,
            "onboard_dso_party",
            "Onboard to DSO party hosting and decentralized namespace membership",
            SvConnection(
              sponsorSvConfig.adminApi,
              upgradesConfig,
              retryProvider,
              loggerFactory,
            ).flatMap { svConnection =>
              logger.info(s"Proposing party allocation to participant $participantId")
              (for {
                partyToParticipantProposal <- participantAdminConnection
                  .ensurePartyToParticipantAdditionProposal(
                    synchronizerId,
                    dsoParty,
                    participantId,
                  )
                _ = logger.info("Disconnecting from all domains")
                _ <- participantAdminConnection.disconnectFromAllSynchronizers()
                _ = logger.info("candidate SV participant disconnected from global domain")
                acsSnapshot <- retryProvider
                  .retry(
                    RetryFor.WaitingOnInitDependencyLong,
                    "authorize_dso_party",
                    "authorize DSO party hosting on sponsor",
                    snapshotDownload.prepareAndDownload(
                      svConnection
                        .prepareDsoPartyHostingSnapshot(svParty)
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
                          case Right(preparation) => Future.successful(preparation)
                        },
                      svConnection.downloadOnboardingSnapshot,
                    ),
                    logger,
                  )
                  .recoverWith {
                    case proposalNotFound: OnboardSvPartyMigrationAuthorizeProposalNotFound =>
                      // Reconnect so that the participant gets its state in sync before the next retry
                      logger.info(
                        "Reconnecting to global domain so that the proposal can be recreated from the latest base."
                      )
                      for {
                        _ <- participantAdminConnection.connectSynchronizer(synchronizerAlias)
                        _ <- retryProvider.waitUntil(
                          RetryFor.WaitingOnInitDependency,
                          "party_hosting_serial_observed",
                          s"Serial ${proposalNotFound.partyToParticipantMappingSerial} expected by sponsor is observed",
                          participantAdminConnection
                            .getPartyToParticipant(
                              synchronizerId,
                              dsoParty,
                              topologySnapshot = TopologySnapshot.Sequenced,
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
                acsSnapshot
              }).andThen(_ => svConnection.close())
            },
            logger,
          )
          _ = logger.info(
            "Received Acs snapshot from sponsor, importing into candidate participant"
          )
          _ <- participantAdminConnection.importPartyAcsFromFile(
            acsSnapshot,
            synchronizerId,
            dsoParty,
          )
          _ = logger.info(
            "Imported Acs snapshot from sponsor SV participant to candidate participant"
          )
          _ <- participantAdminConnection.reconnectAllSynchronizers()
          // Explicitly connect to global domain as that has manualConnect=false
          _ <- participantAdminConnection.connectSynchronizer(synchronizerAlias)
          _ = logger.info("candidate SV participant reconnected to global domain")
          _ <- dsoPartyHosting.waitForDsoPartyToParticipantAuthorization(
            synchronizerId,
            participantId,
            RetryFor.Automation,
          )
          _ = logger.info(
            s"DSO party is now hosted in the candidate SV participant $participantId"
          )
        } yield Right(())).andThen(_ => snapshotDownload.delete())
      case None =>
        Future.successful(Left("unexpected onboarding config"))
    }
  }

  private def getSponsorSvConfig(
      onboardingConfig: Option[SvOnboardingConfig]
  ): Option[SvAppClientConfig] =
    onboardingConfig match {
      case Some(SvOnboardingConfig.JoinWithKey(_, sponsorSv, _, _, _)) =>
        Some(sponsorSv)
      case _ => None
    }

}
