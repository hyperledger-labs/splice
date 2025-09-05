// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import cats.data.EitherT
import com.digitalasset.base.error.utils.ErrorDetails
import org.lfdecentralizedtrust.splice.codegen.java.splice.amulet.FeaturedAppRight
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting.DsoPartyMigrationFailure
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.admin.repair.RepairServiceError
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.{Status, StatusRuntimeException}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion.SpliceLedgerConnectionPriority

import java.time.Instant
import scala.concurrent.{ExecutionContextExecutor, Future}

class DsoPartyMigration(
    svStoreWithIngestion: AppStoreWithIngestion[SvSvStore],
    dsoStoreWithIngestion: AppStoreWithIngestion[SvDsoStore],
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    dsoPartyHosting: DsoPartyHosting,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContextExecutor
) extends NamedLogging {

  private val dsoStore = dsoStoreWithIngestion.store
  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty
  private val partyHosting = new SponsorDsoPartyHosting(
    participantAdminConnection,
    dsoParty,
    dsoPartyHosting,
    loggerFactory,
  )

  def authorizeParticipantForHostingDsoParty(
      participantId: ParticipantId
  )(implicit tc: TraceContext): EitherT[Future, DsoPartyMigrationFailure, ByteString] = {
    logger.info(s"Sponsor SV authorizing DSO party to participant $participantId")
    for {
      dsoRules <- EitherT.liftF(dsoStore.getDsoRules())
      // this will wait until the PartyToParticipant state change completed
      authorizedAt <- partyHosting
        .authorizeDsoPartyToParticipant(
          dsoRules.domain,
          participantId,
        )
      _ = logger.info(
        s"DSO party was authorized on $participantId, downloading snapshot at time $authorizedAt."
      )
      acsBytes <- EitherT.liftF(downloadSnapshotFromTime(authorizedAt, dsoRules.domain))
    } yield {
      acsBytes
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def downloadSnapshotFromTime(
      authorizedAt: Instant,
      decentralizedSynchronizer: SynchronizerId,
  )(implicit tc: TraceContext): Future[ByteString] = {
    def submitDummyTransaction(): Future[Unit] =
      svStoreWithIngestion
        .connection(SpliceLedgerConnectionPriority.Low)
        .submit(
          Seq(svParty),
          Seq.empty,
          // The transaction here is arbitrary with the restriction that it should not have the DSO as a stakeholder.
          // FeaturedAppRight just happens to be one of the simplest templates we have.
          new FeaturedAppRight(svParty.toProtoPrimitive, svParty.toProtoPrimitive).createAnd
            .exerciseArchive(),
        )
        .withSynchronizerId(decentralizedSynchronizer)
        .noDedup
        .yieldUnit()
    // Acquiring the ACS snapshot is tricky due to two issues:
    // 1. The snapshot can only be acquired at a "clean" timestamp which means there are no outstanding ACS commitments.
    //    To ensure that the timestamp will eventually be clean we need to submit a transaction visible to the participant (submitDummyTransaction) and
    //    retry the download afterwards. Note that due to the second issue, this transaction must not change contracts with DSO as the stakeholder.
    // 2. Concurrent ACS pruning in Canton can prune the data for that timestamp. In that case, we give up.
    for {
      snapshot <- {
        retryProvider.retry(
          RetryFor.ClientCalls,
          "download_acs_snapshot",
          show"Download ACS snapshot for DSO at $authorizedAt",
          participantAdminConnection
            .downloadAcsSnapshot(
              Set(dsoParty),
              filterSynchronizerId = Some(decentralizedSynchronizer),
              timestamp = Some(authorizedAt),
            )
            .recoverWith { case ex: StatusRuntimeException =>
              val errorDetails = ErrorDetails.from(ex: StatusRuntimeException)
              for {
                // Special case some exceptions
                _ <- MonadUtil.sequentialTraverse_(errorDetails) {
                  case ErrorDetails
                        .ErrorInfoDetail(RepairServiceError.UnavailableAcsSnapshot.id, metadata) =>
                    val msg =
                      s"Requested record time $authorizedAt has been pruned: $metadata, make sure that journal-garbage-collection-delay is configured sufficiently high"
                    logger.warn(msg)
                    Future.failed(Status.INVALID_ARGUMENT.withDescription(msg).asRuntimeException())
                  case ErrorDetails.ErrorInfoDetail(
                        RepairServiceError.InvalidAcsSnapshotTimestamp.id,
                        metadata,
                      ) =>
                    logger.info(
                      s"Requested record time $authorizedAt is not yet clean: $metadata, submitting dummy transaction"
                    )
                    submitDummyTransaction()
                  case _ => Future.unit
                }
              } yield {
                // Rethrow everything else
                throw ex
              }
            },
          logger,
        )
      }
    } yield snapshot

  }

}
