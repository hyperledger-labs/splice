// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.sponsor

import cats.data.EitherT
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
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
      participantId: ParticipantId,
      beforeActivationOffset: NonNegativeLong,
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
      _ = logger.info(
        s"DSO party was authorized on $participantId, downloading snapshot at offset $beforeActivationOffset."
      )
      acsBytes <- EitherT.liftF(
        downloadSnapshotFromTime(
          participantId,
          beforeActivationOffset,
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
      beforeActivationOffset: NonNegativeLong,
      decentralizedSynchronizer: SynchronizerId,
  )(implicit tc: TraceContext): Future[ByteString] = {
    for {
      snapshot <- {
        retryProvider.retry(
          RetryFor.ClientCalls,
          "download_acs_snapshot",
          show"Download ACS snapshot for DSO at $beforeActivationOffset",
          participantAdminConnection
            .exportPartyAcs(
              dsoParty,
              synchronizerId = decentralizedSynchronizer,
              targetParticipantId = targetParticipantId,
              beforeActivationOffset = beforeActivationOffset,
            ),
          logger,
        )
      }
    } yield snapshot

  }

}
