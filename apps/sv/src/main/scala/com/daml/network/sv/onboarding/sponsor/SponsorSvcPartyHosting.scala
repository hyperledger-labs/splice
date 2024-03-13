package com.daml.network.sv.onboarding.sponsor

import cats.data.{EitherT, OptionT}
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AllProposals
import com.daml.network.environment.TopologyAdminConnection.{AuthorizedStateChanged, TopologyResult}
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.onboarding.SvcPartyHosting
import com.daml.network.sv.onboarding.SvcPartyHosting.{
  RequiredProposalNotFound,
  SvcPartyMigrationFailure,
}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.transaction.PartyToParticipantX
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

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
          RetryFor.ClientCalls,
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
      case SponsorSvcPartyHosting.ValidAcceptedState(accepted) =>
        EitherT.right(Future.successful(accepted))
      case SponsorSvcPartyHosting.ValidProposal(proposal) =>
        EitherT(
          participantAdminConnection
            .ensurePartyToParticipantAdditionProposalWithSerial(
              domainId,
              party,
              newParticipant,
              PositiveInt.tryCreate(proposal.base.serial.value - 1),
              signedBy,
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
    SponsorSvcPartyHosting.ValidProposalOrAcceptedState,
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
              proposals
                .find(proposal =>
                  proposal.mapping.participantIds
                    .contains(participantId)
                )
                .map[SponsorSvcPartyHosting.ValidProposalOrAcceptedState](
                  SponsorSvcPartyHosting.ValidProposal(_)
                )
            }
        }
    )
      .orElse(
        OptionT
          .liftF(partyToParticipantAcceptedState)
          .filter { partyToParticipant =>
            partyToParticipant.mapping.participantIds.contains(
              participantId
            )
          }
          .map(SponsorSvcPartyHosting.ValidAcceptedState(_))
      )
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

object SponsorSvcPartyHosting {
  sealed abstract class ValidProposalOrAcceptedState extends Product with Serializable
  final case class ValidProposal(proposal: TopologyResult[PartyToParticipantX])
      extends ValidProposalOrAcceptedState
  final case class ValidAcceptedState(accepted: TopologyResult[PartyToParticipantX])
      extends ValidProposalOrAcceptedState
}
