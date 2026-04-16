// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import cats.data.EitherT
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
}
import org.lfdecentralizedtrust.splice.store.AppStoreWithIngestion
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting
import org.lfdecentralizedtrust.splice.sv.onboarding.DsoPartyHosting.DsoPartyMigrationFailure
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.{ParticipantId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.ByteString
import io.grpc.Status

import java.time.Instant
import scala.annotation.unused
import scala.concurrent.{ExecutionContextExecutor, Future}

class DsoPartyMigration(
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
        downloadSnapshotFromTime(
          participantId,
          activationTime,
          dsoRules.domain,
        )
      )
    } yield {
      acsBytes
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  private def downloadSnapshotFromTime(
      targetParticipantId: ParticipantId,
      activationTime: Instant,
      decentralizedSynchronizer: SynchronizerId,
  )(implicit tc: TraceContext): Future[ByteString] = {
    for {
      snapshot <- {
        retryProvider.retry(
          RetryFor.ClientCalls,
          "download_acs_snapshot",
          show"Download ACS snapshot for DSO at $activationTime",
          participantAdminConnection
            .exportPartyAcs(
              dsoParty,
              synchronizerId = decentralizedSynchronizer,
              targetParticipantId = targetParticipantId,
              activationTime = activationTime,
            ),
          logger,
        )
      }
    } yield snapshot

  }

}
