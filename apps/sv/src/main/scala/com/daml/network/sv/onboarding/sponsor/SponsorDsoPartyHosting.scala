// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.onboarding.sponsor

import cats.data.{EitherT, OptionT}
import com.daml.network.environment.TopologyAdminConnection.TopologyTransactionType.AllProposals
import com.daml.network.environment.TopologyAdminConnection.{AuthorizedStateChanged, TopologyResult}
import com.daml.network.environment.{ParticipantAdminConnection, RetryFor}
import com.daml.network.sv.onboarding.DsoPartyHosting
import com.daml.network.sv.onboarding.DsoPartyHosting.{
  RequiredProposalNotFound,
  DsoPartyMigrationFailure,
}
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.crypto.Fingerprint
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.transaction.PartyToParticipant
import com.digitalasset.canton.topology.{DomainId, ParticipantId, PartyId}
import com.digitalasset.canton.tracing.TraceContext

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

class SponsorDsoPartyHosting(
    participantAdminConnection: ParticipantAdminConnection,
    dsoParty: PartyId,
    dsoPartyHosting: DsoPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  def authorizeDsoPartyToParticipant(
      domain: DomainId,
      participantId: ParticipantId,
      signedBy: Fingerprint,
  )(implicit traceContext: TraceContext): EitherT[Future, DsoPartyMigrationFailure, Instant] =
    for {
      _ <- proposePartyHostingAndEnsureAuthorized(
        domain,
        dsoParty,
        participantId,
        signedBy,
      )
      authorizedAt <- EitherT.liftF(
        dsoPartyHosting.waitForDsoPartyToParticipantAuthorization(
          domain,
          participantId,
          RetryFor.ClientCalls,
        )
      )
    } yield {
      logger.info(
        s"Party $dsoParty is authorized on participant $participantId"
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
  ): EitherT[Future, DsoPartyMigrationFailure, TopologyResult[PartyToParticipant]] = {
    validateProposalForNewSv(domainId, newParticipant).flatMap {
      case SponsorDsoPartyHosting.ValidAcceptedState(accepted) =>
        EitherT.right(Future.successful(accepted))
      case SponsorDsoPartyHosting.ValidProposal(proposal) =>
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

  private def validateProposalForNewSv(
      domainId: DomainId,
      participantId: ParticipantId,
  )(implicit tc: TraceContext): EitherT[
    Future,
    DsoPartyMigrationFailure,
    SponsorDsoPartyHosting.ValidProposalOrAcceptedState,
  ] = {
    val partyToParticipantAcceptedState =
      participantAdminConnection.getPartyToParticipant(domainId, dsoParty)
    OptionT(
      participantAdminConnection
        .listPartyToParticipant(
          domainId.filterString,
          filterParty = dsoParty.filterString,
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
                .map[SponsorDsoPartyHosting.ValidProposalOrAcceptedState](
                  SponsorDsoPartyHosting.ValidProposal(_)
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
          .map(SponsorDsoPartyHosting.ValidAcceptedState(_))
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

object SponsorDsoPartyHosting {
  sealed abstract class ValidProposalOrAcceptedState extends Product with Serializable
  final case class ValidProposal(proposal: TopologyResult[PartyToParticipant])
      extends ValidProposalOrAcceptedState
  final case class ValidAcceptedState(accepted: TopologyResult[PartyToParticipant])
      extends ValidProposalOrAcceptedState
}
