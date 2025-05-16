// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.setup

import cats.data.EitherT
import cats.implicits.catsSyntaxOptionId
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  DarResource,
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  ParticipantPermission,
  PartyToParticipant,
  TopologyChangeOp,
}
import com.digitalasset.canton.tracing.TraceContext
import com.google.protobuf.ByteString
import io.grpc.Status

import scala.concurrent.{ExecutionContextExecutor, Future}

class ParticipantPartyMigrator(
    connection: BaseLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    decentralizedSynchronizerAlias: SynchronizerAlias,
    retryProvider: RetryProvider,
    override val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor,
    traceContext: TraceContext,
) extends NamedLogging {

  def migrate(
      nodeIdentitiesDump: NodeIdentitiesDump,
      validatorPartyHint: String,
      ledgerApiUser: String,
      synchronizerAlias: SynchronizerAlias,
      getAcsSnapshot: PartyId => Future[ByteString],
      requiredDars: Seq[DarResource] = Seq.empty,
      overridePartiesToMigrate: Option[Seq[PartyId]],
  ): Future[Unit] = {
    for {
      participantId <- participantAdminConnection.getParticipantId()
      oldParticipantId = ParticipantId(nodeIdentitiesDump.id.uid)
      synchronizerId <- participantAdminConnection.getSynchronizerId(synchronizerAlias)
      validatorPartyId = toPartyId(validatorPartyHint, participantId)
      partiesToMigrate <- getPartiesToMigrate(
        overridePartiesToMigrate,
        synchronizerId,
        oldParticipantId,
      )
      // We only need to remove the domain trust certificate of the old participant
      // if we're planning to migrate the participant admin party.
      // Note that this method also unhosts all `partiesToMigrate` from the old participant.
      _ <- removeDomainTrustCertificateIfNeeded(
        partiesToMigrate,
        synchronizerId,
        synchronizerAlias,
        oldParticipantId,
      )
      _ = logger.info(s"Hosting $partiesToMigrate on $participantId")
      _ <- ensurePartiesMigrated(
        synchronizerAlias,
        partiesToMigrate,
        participantId,
      )
      // There isn't a great way to check if we already imported the ACS so instead we check if the user already has a primary party
      // which is set afterwards. If things really go wrong during this step, we can always start over on a fresh participant.
      primaryPartyO <- connection.getOptionalPrimaryParty(ledgerApiUser)
      _ <- primaryPartyO match {
        case Some(_) =>
          logger.info("Party migration already complete, continuing")
          Future.unit
        case None =>
          logger.info(s"Importing ACS for party ids $partiesToMigrate from scan")
          for {
            _ <- importAcs(partiesToMigrate, getAcsSnapshot, requiredDars)
            _ <- connection.ensureUserHasPrimaryParty(ledgerApiUser, validatorPartyId)
          } yield ()
      }
    } yield ()
  }

  private def toPartyId(partyHint: String, participantId: ParticipantId) = PartyId(
    UniqueIdentifier.tryCreate(partyHint, participantId.uid.namespace)
  )

  private def getPartiesToMigrate(
      overridePartiesToMigrate: Option[Seq[PartyId]],
      synchronizerId: SynchronizerId,
      oldParticipantId: ParticipantId,
  ): Future[Seq[PartyId]] = {
    overridePartiesToMigrate match {
      case Some(parties) =>
        logger.info(s"Using parties to migrate from config: $parties")
        Future.successful(parties)
      case None =>
        logger.info(
          "No overridden parties to migrate, using all parties still hosted on the old participant"
        )
        for {
          allHosted <- participantAdminConnection.listPartyToParticipant(
            TopologyStoreId.SynchronizerStore(synchronizerId).some,
            filterParticipant = oldParticipantId.uid.toProtoPrimitive,
          )
          (ignored, unhostedOrSingleHosted) = allHosted.partition(_.mapping.participants.size > 1)
          _ = if (ignored.nonEmpty)
            logger.info(
              s"ignoring parties that are hosted by more than one participant: ${ignored.map(_.mapping.partyId)}"
            )
        } yield {
          unhostedOrSingleHosted.map(_.mapping.partyId)
        }
    }
  }

  private def removeDomainTrustCertificateIfNeeded(
      partiesToMigrate: Seq[PartyId],
      synchronizerId: SynchronizerId,
      synchronizerAlias: SynchronizerAlias,
      oldParticipantId: ParticipantId,
  ): Future[Unit] = {
    partiesToMigrate.find(
      _.uid.identifier == oldParticipantId.uid.identifier
    ) match {
      case Some(adminPartyId) =>
        removeDomainTrustCertificate(
          adminPartyId,
          partiesToMigrate,
          synchronizerId,
          synchronizerAlias,
          oldParticipantId,
        )
      case None =>
        logger.info(
          s"We won't be migrating the participant admin party, " +
            "so not removing domain trust certificate."
        )
        Future.unit
    }
  }

  private def removeDomainTrustCertificate(
      adminPartyId: PartyId,
      partiesToMigrate: Seq[PartyId],
      synchronizerId: SynchronizerId,
      synchronizerAlias: SynchronizerAlias,
      oldParticipantId: ParticipantId,
  ): Future[Unit] = {
    logger.info(
      s"Preparing to remove domain trust certificate because we will be migrating ${adminPartyId}."
    )
    for {
      // Unhosting all parties first is a prerequisite for removing the domain trust certificate
      allHostedParties <- participantAdminConnection
        .listPartyToParticipant(
          TopologyStoreId.SynchronizerStore(synchronizerId).some,
          filterParticipant = oldParticipantId.uid.toProtoPrimitive,
        )
        .map(_.map(_.mapping.partyId))
      missedHostedParties = allHostedParties.filterNot(partiesToMigrate.toSet.contains)
      _ = if (missedHostedParties.nonEmpty)
        sys.error(
          s"Parties to migrate $partiesToMigrate include the participant admin party $adminPartyId " +
            s"but are missing the additional hosted parties: $missedHostedParties; " +
            "either avoid migrating the admin party or ensure that all parties will be unhosted."
        )
      // We unhost the admin party last to avoid breaking the participant in case we fail unhosting some other party.
      partiesToMigrateExAdminParty = partiesToMigrate.filterNot(_ == adminPartyId)
      _ = logger.info(s"Unhosting $partiesToMigrateExAdminParty from $oldParticipantId")
      _ <- ensurePartiesUnhosted(
        synchronizerAlias,
        partiesToMigrateExAdminParty,
        oldParticipantId,
      )
      _ = logger.info(s"Unhosting $adminPartyId from $oldParticipantId")
      _ <- ensurePartiesUnhosted(synchronizerAlias, Seq(adminPartyId), oldParticipantId)
      _ = logger.info("Removing domain trust certificate.")
      _ <- participantAdminConnection.ensureSynchronizerTrustCertificateRemoved(
        RetryFor.WaitingOnInitDependency,
        synchronizerId,
        oldParticipantId.member,
      )
    } yield ()

  }

  private def ensurePartiesMigrated(
      synchronizerAlias: SynchronizerAlias,
      partyIds: Seq[PartyId],
      participantId: ParticipantId,
  ): Future[Unit] = {
    Future
      .traverse(partyIds) { partyId =>
        for {
          synchronizerId <- participantAdminConnection.getSynchronizerId(synchronizerAlias)
          _ <- participantAdminConnection.ensureTopologyMapping[PartyToParticipant](
            store = TopologyStoreId.SynchronizerStore(synchronizerId),
            s"Party $partyId is hosted on participant $participantId",
            EitherT {
              participantAdminConnection
                .getPartyToParticipant(synchronizerId, partyId, None)
                .flatMap { result =>
                  result.mapping.participants match {
                    case Seq(participant) =>
                      if (
                        participant.participantId == participantId && result.base.operation == TopologyChangeOp.Replace
                      ) {
                        Future.successful(Right(result))
                      } else {
                        Future.successful(Left(result))
                      }
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
                PartyToParticipant.tryCreate(
                  partyId = partyId,
                  threshold = PositiveInt.one,
                  participants =
                    Seq(HostingParticipant(participantId, ParticipantPermission.Submission)),
                )
              ),
            retryFor = RetryFor.WaitingOnInitDependency,
          )
        } yield ()
      }
      .map(_ => ())
  }

  private def ensurePartiesUnhosted(
      synchronizerAlias: SynchronizerAlias,
      partyIds: Seq[PartyId],
      participantId: ParticipantId,
  ): Future[Unit] = {
    participantAdminConnection.getSynchronizerId(synchronizerAlias).flatMap { synchronizerId =>
      Future
        .traverse(partyIds) { partyId =>
          for {
            _ <- participantAdminConnection.ensurePartyToParticipantRemoved(
              RetryFor.WaitingOnInitDependency,
              synchronizerId,
              partyId,
              participantId,
            )
          } yield ()
        }
        .map(_ => ())
    }
  }

  private def importAcs(
      partyIds: Seq[PartyId],
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
          val availablePackageIds = dars.map(_.mainPackageId)
          if (!requiredDars.forall(dar => availablePackageIds.contains(dar.packageId)))
            throw Status.FAILED_PRECONDITION
              .withDescription(
                s"Required dars $requiredDars are not yet available"
              )
              .asRuntimeException()
        },
        logger,
      )
      _ <- participantAdminConnection.disconnectFromAllDomains()
      _ <- Future.traverse(partyIds) { partyId =>
        for {
          acsSnapshot <- getAcsSnapshot(partyId)
          _ <- participantAdminConnection.uploadAcsSnapshot(acsSnapshot)
        } yield ()
      }
      _ <- participantAdminConnection.reconnectAllDomains()
      _ <- participantAdminConnection.connectDomain(decentralizedSynchronizerAlias)
      _ = logger.info("ACS import complete")
    } yield ()
  }
}
