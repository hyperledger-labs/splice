// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.setup

import cats.data.EitherT
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
import com.digitalasset.canton.topology.{ParticipantId, PartyId, UniqueIdentifier}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  ParticipantPermission,
  PartyToParticipant,
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
    val oldParticipantId = nodeIdentitiesDump.id
    for {
      participantId <- participantAdminConnection.getParticipantId()
      validatorPartyId = toPartyId(validatorPartyHint, participantId)
      synchronizerId <- participantAdminConnection.getSynchronizerId(synchronizerAlias)
      allPartyToParticipants <- participantAdminConnection
        .listPartyToParticipant(
          synchronizerId.filterString,
          filterParticipant = oldParticipantId.uid.toProtoPrimitive,
        )
      partyToParticipants =
        overridePartiesToMigrate.fold(allPartyToParticipants) { overrideParties =>
          val overridePartiesSet = overrideParties.toSet
          allPartyToParticipants.filter(t => overridePartiesSet.contains(t.mapping.partyId))
        }
      (topologyTxs, topologyTxsToIgnore) = partyToParticipants.partition(
        _.mapping.participants.size == 1
      )
      _ = if (topologyTxsToIgnore.nonEmpty)
        logger.info(
          s"ignoring parties that is not hosted by a single participant: ${topologyTxsToIgnore
              .map(_.mapping.partyId)}"
        )
      partyIdsToMigrate = topologyTxs.map(_.mapping.partyId).toSet
      partyIdsAlreadyMigrated <- participantAdminConnection
        .listPartyToParticipant(
          synchronizerId.filterString,
          filterParticipant = participantId.uid.toProtoPrimitive,
        )
        .map(_.filter(_.mapping.participants.size == 1).map(_.mapping.partyId).toSet)
      _ = if (partyIdsAlreadyMigrated.nonEmpty)
        logger.info(
          s"These party ids are already migrated to the participant $participantId: $partyIdsAlreadyMigrated"
        )
      missingPartiesToMigrate = overridePartiesToMigrate
        .map(_.toSet)
        .getOrElse(Set.empty)
        .diff(partyIdsToMigrate)
        .diff(partyIdsAlreadyMigrated)

      _ = if (missingPartiesToMigrate.nonEmpty)
        sys.error(
          s"Parties $missingPartiesToMigrate is neither migrated nor found from previous topology txs"
        )
      _ <-
        if (partyIdsToMigrate.isEmpty) {
          logger.info("Party ids already migrated")
          Future.unit
        } else {
          logger.info(s"Unhosting $partyIdsToMigrate on $participantId")
          for {
            // This is a prerequisite for removing the domain trust certificate
            _ <- ensurePartiesUnhosted(
              synchronizerAlias,
              partyIdsToMigrate.toSeq,
            )
            // Remove the domain trust certificate of the old participant.
            // This is required to migrate the admin party of that node.
            // We just always do it even if the admin party is not migrated
            // to have fewer special cases
            _ <- participantAdminConnection.ensureSynchronizerTrustCertificateRemoved(
              RetryFor.WaitingOnInitDependency,
              synchronizerId,
              oldParticipantId.member,
            )
            _ = logger.info(s"Hosting $partyIdsToMigrate on $participantId")
            _ <- ensurePartiesMigrated(
              synchronizerAlias,
              partyIdsToMigrate.toSeq,
              participantId,
            )
          } yield ()
        }
      // There isn't a great way to check if we already imported the ACS so instead we check if the user already has a primary party
      // which is set afterwards. If things really go wrong during this step, we can always start over on a fresh participant.
      primaryPartyO <- connection.getOptionalPrimaryParty(ledgerApiUser)
      _ <- primaryPartyO match {
        case Some(_) =>
          logger.info("Party migration already complete, continuing")
          Future.unit
        case None =>
          logger.info(s"Importing ACS for party ids $partyIdsToMigrate from scan")
          for {
            _ <- importAcs(partyIdsToMigrate.toSeq, getAcsSnapshot, requiredDars)
            _ <- connection.ensureUserHasPrimaryParty(ledgerApiUser, validatorPartyId)
          } yield ()
      }
    } yield ()
  }

  private def toPartyId(partyHint: String, participantId: ParticipantId) = PartyId(
    UniqueIdentifier.tryCreate(partyHint, participantId.uid.namespace)
  )

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
              participantAdminConnection.getPartyToParticipant(synchronizerId, partyId).flatMap {
                result =>
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
  ): Future[Unit] = {
    participantAdminConnection.getSynchronizerId(synchronizerAlias).flatMap { synchronizerId =>
      Future
        .traverse(partyIds) { partyId =>
          for {
            _ <- participantAdminConnection.ensurePartyToParticipantRemoved(
              RetryFor.WaitingOnInitDependency,
              synchronizerId,
              partyId,
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
          val availablePackageIds = dars.map(_.darId)
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
