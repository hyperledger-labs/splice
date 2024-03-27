package com.daml.network.setup

import cats.data.EitherT
import com.daml.network.environment.{
  BaseLedgerConnection,
  DarResource,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{Identifier, ParticipantId, PartyId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  ParticipantPermission,
  PartyToParticipantX,
}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

class ParticipantPartyMigrator(
    connection: BaseLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    decentralizedSynchronizerAlias: DomainAlias,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    traceContext: TraceContext,
) extends NamedLogging {

  def migrate(
      partyHint: String,
      ledgerApiUser: String,
      domainAlias: DomainAlias,
      getAcsSnapshot: PartyId => Future[ByteString],
      requiredDars: Seq[DarResource] = Seq.empty,
  ): Future[PartyId] = {
    for {
      participantId <- participantAdminConnection.getParticipantId()
      partyId = PartyId(
        UniqueIdentifier(Identifier.tryCreate(partyHint), participantId.uid.namespace)
      )
      // There isn't a great way to check if we already imported the ACS so instead we check if the user already has a primary party
      // which is set afterwards. If things really go wrong during this step, we can always start over on a fresh participnat.
      primaryPartyO <- connection.getOptionalPrimaryParty(ledgerApiUser)
      _ <- primaryPartyO match {
        case Some(_) =>
          logger.info("Party migration already complete, continuing")
          Future.unit
        case None =>
          logger.info(s"Migrating party $partyId to new participant")
          for {
            _ <- migrateParty(
              domainAlias,
              partyId,
              participantId,
              getAcsSnapshot,
              requiredDars,
            )
            _ <- connection.ensureUserHasPrimaryParty(ledgerApiUser, partyId)
          } yield partyId
      }
    } yield partyId
  }

  // TODO(#10988): extend it to migration multiple parties
  private def migrateParty(
      domainAlias: DomainAlias,
      partyId: PartyId,
      participantId: ParticipantId,
      getAcsSnapshot: PartyId => Future[ByteString],
      requiredDars: Seq[DarResource],
  ): Future[PartyId] = {
    for {
      domainId <- participantAdminConnection.getDomainId(domainAlias)
      _ <- participantAdminConnection.ensureTopologyMapping[PartyToParticipantX](
        store = TopologyStoreId.DomainStore(domainId),
        s"Party $partyId is hosted on participant $participantId",
        EitherT {
          participantAdminConnection.getPartyToParticipant(domainId, partyId).flatMap { result =>
            result.mapping.participants match {
              case Seq(participant) if participant.participantId != participantId =>
                Future.successful(Left(result))
              case Seq(participant) if participant.participantId == participantId =>
                Future.successful(Right(result))
              case participants =>
                Future.failed(
                  Status.INTERNAL
                    .withDescription(
                      s"Party $partyId is hosted on multiple participant, giving up: $participants"
                    )
                    .asRuntimeException()
                )
            }
          }
        },
        _ =>
          Right(
            PartyToParticipantX(
              partyId = partyId,
              domainId = None,
              threshold = PositiveInt.one,
              participants =
                Seq(HostingParticipant(participantId, ParticipantPermission.Submission)),
              groupAddressing = false,
            )
          ),
        signedBy = participantId.uid.namespace.fingerprint,
        retryFor = RetryFor.WaitingOnInitDependency,
      )
      _ = logger.info(s"Importing ACS for party $partyId from scan")
      _ <- importAcs(partyId, getAcsSnapshot, requiredDars)
    } yield partyId
  }

  private def importAcs(
      partyId: PartyId,
      getAcsSnapshot: PartyId => Future[ByteString],
      requiredDars: Seq[DarResource],
  ): Future[Unit] = {
    for {
      // dars are uploaded async during the init phase
      _ <- retryProvider.waitUntil(
        RetryFor.WaitingOnInitDependency,
        "dars_uploaded",
        "Required dars are uploaded",
        participantAdminConnection.listDars().map { dars =>
          val availablesHases = dars.map(_.hash)
          if (!requiredDars.forall(dar => availablesHases.contains(dar.darHash.toHexString)))
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Required dars $requiredDars are not yet available"
              )
              .asRuntimeException()
        },
        logger,
      )
      _ <- participantAdminConnection.disconnectFromAllDomains()
      acsSnapshot <- getAcsSnapshot(partyId)
      _ <- participantAdminConnection.uploadAcsSnapshot(acsSnapshot)
      _ <- participantAdminConnection.reconnectAllDomains()
      _ <- participantAdminConnection.connectDomain(decentralizedSynchronizerAlias)
      _ = logger.info("ACS import complete")
    } yield ()
  }
}
