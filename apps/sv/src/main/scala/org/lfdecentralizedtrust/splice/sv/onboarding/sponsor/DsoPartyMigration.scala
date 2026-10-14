// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import cats.data.EitherT
import com.digitalasset.base.error.utils.ErrorDetails
import org.lfdecentralizedtrust.splice.codegen.java.splice.externalpartyamuletrules.ExternalPartyAmuletRules
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
}
import com.digitalasset.canton.participant.admin.party.PartyManagementServiceError
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting.DsoPartyMigrationFailure
import org.lfdecentralizedtrust.splice.sv.onboarding.sponsor.SvOnboardingSnapshotService.SnapshotKey
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.Status

import io.grpc.StatusRuntimeException
import java.nio.file.Path
import java.time.Instant
import scala.annotation.unused
import scala.concurrent.{ExecutionContextExecutor, Future}

class DsoPartyMigration(
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    participantAdminConnection: ParticipantAdminConnection,
    @unused ledgerClient: SpliceLedgerClient,
    retryProvider: RetryProvider,
    dsoPartyHosting: DsoPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  private val dsoStore = dsoStoreWithIngestion.store
  private val dsoParty = dsoStore.key.dsoParty
  private val svParty = dsoStore.key.svParty
  private val partyHosting = new SponsorDsoPartyHosting(
    participantAdminConnection,
    dsoParty,
    dsoPartyHosting,
    loggerFactory,
  )

  def authorizeParticipantForHostingDsoParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext): EitherT[Future, DsoPartyMigrationFailure, Seq[ByteString]] = {
    logger.info(s"Sponsor SV authorizing DSO party to participant $participantId")
    for {
      dsoRules <- EitherT.liftF(dsoStore.getDsoRules())
      // this will wait until the PartyToParticipant state change completed
      _ <- partyHosting
        .authorizeDsoPartyToParticipant(
          dsoRules.domain,
          participantId,
        )
      activationTx <- EitherT.liftF(
        participantAdminConnection
          .getDsoPartyToParticipantTransaction(
            dsoRules.domain,
            participantId,
            dsoParty,
          )
          .getOrElseF(
            Future.failed(
              Status.NOT_FOUND
                .withDescription(
                  s"Transaction where the participant $participantId was activated not found."
                )
                .asRuntimeException()
            )
          )
      )
      activationTime = activationTx.base.validFrom
      _ = logger.info(
        s"DSO party was authorized on $participantId, downloading snapshot at time $activationTime."
      )
      acsBytes <- EitherT.liftF(
        exportSnapshotFromTime(activationTime, dsoRules.domain)(
          participantAdminConnection.exportPartyAcs(
            dsoParty,
            synchronizerId = dsoRules.domain,
            targetParticipantId = participantId,
            activationTime = activationTime,
          )
        )
      )
    } yield {
      acsBytes
    }
  }

  def prepareParticipantForHostingDsoParty(
      participantId: ParticipantId
  )(implicit
      tc: TraceContext
  ): EitherT[Future, DsoPartyMigrationFailure, Option[SnapshotKey.Acs]] =
    for {
      dsoRules <- EitherT.liftF(dsoStore.getDsoRules())
      _ <- partyHosting.ensureDsoPartyToParticipantProposalSigned(dsoRules.domain, participantId)
      activationTx <- EitherT.liftF(
        participantAdminConnection
          .getDsoPartyToParticipantTransaction(dsoRules.domain, participantId, dsoParty)
          .value
      )
    } yield activationTx.map(tx =>
      SnapshotKey.Acs(dsoRules.domain, participantId, dsoParty, tx.base.validFrom)
    )

  def exportSnapshotToFile(key: SnapshotKey.Acs, file: Path)(implicit
      tc: TraceContext
  ): Future[Unit] =
    exportSnapshotFromTime(key.activationTime, key.synchronizerId)(
      participantAdminConnection.exportPartyAcsToFile(
        key.party,
        key.synchronizerId,
        key.targetParticipantId,
        key.activationTime,
        file,
      )
    )

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def exportSnapshotFromTime[T](
      activationTime: Instant,
      decentralizedSynchronizer: SynchronizerId,
  )(exportSnapshot: => Future[T])(implicit tc: TraceContext): Future[T] = {

    def submitDummyTransaction(): Future[Unit] =
      svStoreWithIngestion
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(
          Seq(svParty),
          Seq.empty,
          // The transaction here is arbitrary.
          // ExternalPartyAmuletRules just happens to be one of the simplest templates we have.
          new ExternalPartyAmuletRules(svParty.toProtoPrimitive).createAnd
            .exerciseArchive(),
        )
        .withSynchronizerId(decentralizedSynchronizer)
        .noDedup
        .yieldUnit()

    for {
      snapshot <- {
        retryProvider.retry(
          RetryFor.ClientCalls,
          "download_acs_snapshot",
          show"Download ACS snapshot for DSO at $activationTime",
          exportSnapshot
            .recoverWith { case ex: StatusRuntimeException =>
              val errorDetails = ErrorDetails.from(ex: StatusRuntimeException)
              for {
                _ <- MonadUtil.sequentialTraverse_(errorDetails) {
                  case ErrorDetails.ErrorInfoDetail(
                        PartyManagementServiceError.UnprocessedRequestedTimestamp.id,
                        metadata,
                      ) =>
                    logger.info(
                      s"Requested record time $activationTime is not yet clean: $metadata, submitting dummy transaction"
                    )
                    submitDummyTransaction()
                  case _ => Future.unit
                }
              } yield {
                // rethrow to trigger the retry
                throw ex
              }
            },
          logger,
        )
      }
    } yield snapshot

  }

}
