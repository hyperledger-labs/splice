// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.setup

import cats.data.EitherT
import cats.implicits.catsSyntaxOptionId
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.config.RequireTypes.PositiveInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.store.TopologyStoreId
import com.digitalasset.canton.topology.transaction.{
  HostingParticipant,
  ParticipantPermission,
  PartyToParticipant,
  TopologyChangeOp,
}
import com.digitalasset.canton.topology.{ParticipantId, PartyId, SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.google.protobuf.ByteString
import io.grpc.Status
import org.lfdecentralizedtrust.splice.environment.TopologyAdminConnection.TopologyTransactionType.AuthorizedState
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  ParticipantAdminConnection,
  RetryFor,
}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesDump

import scala.concurrent.{ExecutionContextExecutor, Future}

class ParticipantPartyMigrator(
    connection: BaseLedgerConnection,
    participantAdminConnection: ParticipantAdminConnection,
    decentralizedSynchronizerAlias: SynchronizerAlias,
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
      partiesToMigrateFinal <- overridePartiesToMigrate match {
        case Some(_) =>
          Future.successful(partiesToMigrate)
        case None =>
          // Logs warnings
          filterOutUnsupportedParties(
            partiesToMigrate,
            synchronizerId,
            participantId,
            oldParticipantId,
          )
      }
      _ = logger.info(s"Hosting $partiesToMigrate on $participantId")
      _ <- ensurePartiesMigrated(
        synchronizerAlias,
        partiesToMigrateFinal,
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
          logger.info(s"Importing ACS for party ids $partiesToMigrateFinal from scan")
          for {
            _ <- importAcs(partiesToMigrateFinal, getAcsSnapshot)
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
        participantAdminConnection
          .listPartyToParticipant(
            TopologyStoreId.SynchronizerStore(synchronizerId).some,
            filterParticipant = oldParticipantId.uid.toProtoPrimitive,
          )
          .map(_.map(_.mapping.partyId))
    }
  }

  private def filterOutUnsupportedParties(
      parties: Seq[PartyId],
      synchronizerId: SynchronizerId,
      participantId: ParticipantId,
      oldParticipantId: ParticipantId,
  ): Future[Seq[PartyId]] = {
    for {
      filtered1 <- filterOutMultiHostedParties(parties, synchronizerId)
      filtered2 = filterOutPartiesWithDifferentNamespaces(
        filtered1,
        participantId,
        oldParticipantId,
      )
    } yield filtered2
  }

  private def filterOutMultiHostedParties(
      parties: Seq[PartyId],
      synchronizerId: SynchronizerId,
  ): Future[Seq[PartyId]] =
    for {
      mappings <- Future.traverse(parties) { partyId =>
        participantAdminConnection
          .getPartyToParticipant(synchronizerId, partyId, None, AuthorizedState)
          .map(_.mapping)
      }
    } yield {
      val (supported, ignored) = mappings.partition { mapping =>
        mapping.participants.size <= 1
      }
      if (ignored.nonEmpty)
        logger.warn(
          "Ignoring parties that we will not be able to migrate because they are multi-hosted: " +
            s"${ignored.map(_.partyId)}."
        )
      supported.map(_.partyId)
    }

  private def filterOutPartiesWithDifferentNamespaces(
      parties: Seq[PartyId],
      participantId: ParticipantId,
      oldParticipantId: ParticipantId,
  ): Seq[PartyId] = {
    val supportedNamespaces = Set(
      participantId.uid.namespace,
      oldParticipantId.uid.namespace,
    )
    val (supported, ignored) = parties.partition { party =>
      supportedNamespaces.contains(party.uid.namespace)
    }
    if (ignored.nonEmpty)
      logger.warn(
        "Ignoring parties that we will likely not be able to migrate due to an unsupported namespace: " +
          s"${ignored}."
      )
    supported
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
      partiesToMigrateExAdminParty = partiesToMigrate.filterNot(_ == adminPartyId)
      _ = logger.info(s"Unhosting $partiesToMigrateExAdminParty from $oldParticipantId")
      // needs only participant signatures, so works also for external parties
      _ <- ensurePartiesUnhosted(
        synchronizerAlias,
        partiesToMigrateExAdminParty,
        oldParticipantId,
      )
      _ = logger.info(
        s"Removing party mapping for $adminPartyId (was mapping to $oldParticipantId)"
      )
      _ <- participantAdminConnection.ensurePartyToParticipantRemoved(
        RetryFor.WaitingOnInitDependency,
        synchronizerId,
        adminPartyId,
        oldParticipantId,
      )
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
            topologyTransactionType =>
              EitherT {
                participantAdminConnection
                  .getPartyToParticipant(synchronizerId, partyId, None, topologyTransactionType)
                  .flatMap { result =>
                    result.mapping.participants match {
                      case Seq() => Future.successful(Left(result))
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
            _ <- participantAdminConnection.ensurePartyUnhostedFromParticipant(
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
  ): Future[Unit] = {
    for {
      _ <- participantAdminConnection.disconnectFromAllDomains()
      // ACS exports are expensive so do not change this to be parallel.
      _ <- MonadUtil.sequentialTraverse(partyIds) { partyId =>
        for {
          acsSnapshot <- getAcsSnapshot(partyId)
          _ <- participantAdminConnection.uploadAcsSnapshot(Seq(acsSnapshot))
        } yield ()
      }
      _ <- participantAdminConnection.reconnectAllDomains()
      _ <- participantAdminConnection.connectDomain(decentralizedSynchronizerAlias)
      _ = logger.info("ACS import complete")
    } yield ()
  }
}
