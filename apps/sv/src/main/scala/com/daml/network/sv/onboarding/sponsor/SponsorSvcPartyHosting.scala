package com.daml.network.sv.onboarding.sponsor

import cats.data.{EitherT, OptionT}
import cats.syntax.either.*
import com.daml.network.config.CNThresholds
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AllProposals
import com.daml.network.environment.TopologyAdminConnection.{AuthorizedStateChanged, TopologyResult}
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor, TopologyAdminConnection}
import com.daml.network.sv.onboarding.SvcPartyHosting
import com.daml.network.sv.onboarding.SvcPartyHosting.{
  RequiredProposalNotFound,
  SvcPartyMigrationFailure,
}
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  ParticipantPermissionX,
  PartyToParticipantX,
}
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

class SponsorSvcPartyHosting(
    participantAdminConnection: ParticipantAdminConnection,
    svcParty: PartyId,
    svcPartyHosting: SvcPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  def authorizeSvcPartyToParticipant(
      domain: DomainId,
      participantId: ParticipantId,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): EitherT[Future, SvcPartyMigrationFailure, Instant] =
    for {
      _ <- proposePartyHostingAndEnsureAuthorized(
        domain,
        svcParty,
        participantId,
        signedBy,
      )
      authorizedAt <- EitherT.liftF(
        svcPartyHosting.waitForSvcPartyToParticipantAuthorization(
          domain,
          participantId,
        )
      )
    } yield {
      logger.info(
        s"Party $svcParty is authorized on participant $participantId"
      )
      authorizedAt
    }

  private def proposePartyHostingAndEnsureAuthorized(
      domainId: DomainId,
      party: PartyId,
      newParticipant: ParticipantId,
      signedBy: Fingerprint,
  )(implicit
      traceContext: TraceContext
  ): EitherT[Future, SvcPartyMigrationFailure, TopologyResult[PartyToParticipantX]] = {
    validateProposalForNewMember(domainId, newParticipant).flatMap {
      validProposalOrValidAuthorizedState =>
        EitherT(
          participantAdminConnection
            .ensureTopologyMapping[PartyToParticipantX](
              TopologyStoreId.DomainStore(domainId),
              show"Party $party is authorized on $newParticipant",
              EitherT(
                participantAdminConnection
                  .getPartyToParticipant(domainId, party)
                  .map(result =>
                    Either
                      .cond(
                        result.mapping.participants
                          .exists(hosting => hosting.participantId == newParticipant),
                        result,
                        result,
                      )
                      // ensure that the state hasn't changed compared to the accepted state
                      .leftMap { latestAcceptedState =>
                        if (
                          latestAcceptedState.base.serial >= validProposalOrValidAuthorizedState.base.serial
                        )
                          throw AuthorizedStateChanged(latestAcceptedState.base.serial)
                        latestAcceptedState
                      }
                  )
              ),
              previous => {
                val newHostingParticipants = previous.participants.appended(
                  HostingParticipant(
                    newParticipant,
                    ParticipantPermissionX.Submission,
                  )
                )
                Right(
                  previous.copy(
                    participants = newHostingParticipants,
                    threshold = CNThresholds
                      .partyToParticipantThreshold(
                        newHostingParticipants.length
                      ),
                  )
                )
              },
              RetryFor.ClientCalls,
              signedBy,
              isProposal = true,
              recreateOnAuthorizedStateChange = false,
            )
            .map(Right(_))
            .recover { case AuthorizedStateChanged(serial) =>
              logger.debug(
                s"Authorized state serial changed to $serial when adding participant $newParticipant"
              )
              Left(RequiredProposalNotFound(serial))
            }
        )
    }
  }

  private def validateProposalForNewMember(
      domainId: DomainId,
      participantId: ParticipantId,
  )(implicit tc: TraceContext): EitherT[
    Future,
    SvcPartyMigrationFailure,
    TopologyAdminConnection.TopologyResult[PartyToParticipantX],
  ] = {
    val partyToParticipantAcceptedState =
      participantAdminConnection.getPartyToParticipant(domainId, svcParty)
    OptionT(
      participantAdminConnection
        .listPartyToParticipant(
          domainId.filterString,
          filterParty = svcParty.filterString,
          proposals = AllProposals,
        )
        .flatMap { proposals =>
          partyToParticipantAcceptedState
            .map { _ =>
              proposals.find(proposal =>
                proposal.mapping.participantIds
                  .contains(participantId)
              )
            }
        }
    )
      .orElse(OptionT.liftF(partyToParticipantAcceptedState).filter { partyToParticipant =>
        partyToParticipant.mapping.participantIds.contains(
          participantId
        )
      })
      .toRightF {
        partyToParticipantAcceptedState.map { partyToParticipant =>
          logger.debug(s"Required proposal not found, found accepted state: $partyToParticipant")
          RequiredProposalNotFound(
            partyToParticipant.base.serial
          )
        }
      }
  }

}
