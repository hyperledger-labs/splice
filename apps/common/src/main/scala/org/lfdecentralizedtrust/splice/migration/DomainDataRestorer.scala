// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import cats.implicits.catsSyntaxParallelTraverse_
import org.lfdecentralizedtrust.splice.environment.{
  BaseLedgerConnection,
  ParticipantAdminConnection,
  RetryFor,
}
import org.lfdecentralizedtrust.splice.util.UploadablePackage
import com.digitalasset.canton.config.{SynchronizerTimeTrackerConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.util.FutureInstances.parallelFuture
import com.google.protobuf.ByteString

import scala.concurrent.{ExecutionContext, Future}

class DomainDataRestorer(
    participantAdminConnection: ParticipantAdminConnection,
    timeTrackerMinObservationDuration: NonNegativeFiniteDuration,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  /** We assume the domain was not register prior to trying to restore the data.
    */
  def connectDomainAndRestoreData(
      ledgerConnection: BaseLedgerConnection,
      userId: String,
      synchronizerAlias: SynchronizerAlias,
      synchronizerId: SynchronizerId,
      sequencerConnections: SequencerConnections,
      dars: Seq[Dar],
      acsSnapshot: ByteString,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    logger.info("Registering and connecting to new domain")

    // We use user metadata as a dumb storage to track whether we already imported the ACS.
    ledgerConnection
      .lookupUserMetadata(
        userId,
        BaseLedgerConnection.INITIAL_ACS_IMPORT_METADATA_KEY,
      )
      .flatMap {
        case None =>
          val domainConnectionConfig = SynchronizerConnectionConfig(
            synchronizerAlias,
            synchronizerId = Some(synchronizerId),
            sequencerConnections = sequencerConnections,
            manualConnect = true,
            initializeFromTrustedSynchronizer = true,
            timeTracker = SynchronizerTimeTrackerConfig(
              timeTrackerMinObservationDuration
            ),
          )
          // We rely on the calls here being idempotent
          for {
            // Disconnect
            _ <- participantAdminConnection.disconnectFromAllDomains()
            _ <- importDars(dars)
            _ = logger.info("Imported all the dars.")
            _ <-
              participantAdminConnection
                .ensureDomainRegistered(
                  domainConnectionConfig,
                  RetryFor.ClientCalls,
                )
            _ = logger.info("Importing the ACS")
            _ <- importAcs(acsSnapshot)
            _ = logger.info("Imported the ACS")
            _ <- participantAdminConnection.modifySynchronizerConnectionConfigAndReconnect(
              synchronizerAlias,
              config => Some(config.copy(manualConnect = false)),
            )
            _ <- ledgerConnection.ensureUserMetadataAnnotation(
              userId,
              BaseLedgerConnection.INITIAL_ACS_IMPORT_METADATA_KEY,
              "true",
              RetryFor.ClientCalls,
            )
          } yield ()
        case Some(_) =>
          logger.info("Domain is already registered and ACS is imported")
          participantAdminConnection.connectDomain(synchronizerAlias)
      }
  }

  private def importAcs(acs: ByteString)(implicit tc: TraceContext) = {
    participantAdminConnection.uploadAcsSnapshot(
      acs
    )
  }

  private def importDars(dars: Seq[Dar])(implicit tc: TraceContext) = {
    dars
      .map { dar =>
        UploadablePackage.fromByteString(dar.mainPackageId, dar.content)
      }
      .parTraverse_ { dar =>
        participantAdminConnection.uploadDarFileLocally(
          dar,
          RetryFor.WaitingOnInitDependency,
        )
      }
  }

}
