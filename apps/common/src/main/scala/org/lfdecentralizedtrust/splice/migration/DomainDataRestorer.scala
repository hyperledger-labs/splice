// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import org.lfdecentralizedtrust.splice.environment.{ParticipantAdminConnection, RetryFor}
import org.lfdecentralizedtrust.splice.util.UploadablePackage
import com.digitalasset.canton.config.{SynchronizerTimeTrackerConfig, NonNegativeFiniteDuration}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.participant.synchronizer.SynchronizerConnectionConfig
import com.digitalasset.canton.sequencing.SequencerConnections
import com.digitalasset.canton.topology.SynchronizerId
import com.google.protobuf.ByteString

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class DomainDataRestorer(
    participantAdminConnection: ParticipantAdminConnection,
    timeTrackerMinObservationDuration: NonNegativeFiniteDuration,
    timeTrackerObservationLatency: NonNegativeFiniteDuration,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  /** We assume the domain was not register prior to trying to restore the data.
    */
  @nowarn("cat=unused&msg=synchronizerId")
  def connectDomainAndRestoreData(
      synchronizerAlias: SynchronizerAlias,
      synchronizerId: SynchronizerId,
      sequencerConnections: SequencerConnections,
      dars: Seq[Dar],
      acsSnapshot: Seq[ByteString],
      legacyAcsImport: Boolean,
  )(implicit
      tc: TraceContext
  ): Future[Unit] = {
    def restoreData() = {
      val domainConnectionConfig = SynchronizerConnectionConfig(
        synchronizerAlias,
        synchronizerId = None,
        // TODO(#19804) Consider whether we can add back the safeguard here.
        // synchronizerId = Some(synchronizerId),
        sequencerConnections = sequencerConnections,
        manualConnect = true,
        initializeFromTrustedSynchronizer = true,
        timeTracker = SynchronizerTimeTrackerConfig(
          minObservationDuration = timeTrackerMinObservationDuration,
          observationLatency = timeTrackerObservationLatency,
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
        _ <- importAcs(acsSnapshot, legacyAcsImport)
        _ = logger.info("Imported the ACS")
        _ <- participantAdminConnection.modifySynchronizerConnectionConfigAndReconnect(
          synchronizerAlias,
          config => Some(config.copy(manualConnect = false)),
        )
      } yield ()
    }

    // We use user metadata as a dumb storage to track whether we already imported the ACS.
    participantAdminConnection
      .lookupSynchronizerConnectionConfig(
        synchronizerAlias
      )
      .flatMap {
        case None =>
          logger.info("Synchronizer not yet registered, registering and restoring data")
          restoreData()
        case Some(conf) if conf.manualConnect == true =>
          logger.info(
            "Synchronizer registered but manualConnect=true, assuming we crashed during a prior attempt and trying again"
          )
          restoreData()
        case Some(_) =>
          logger.info("Domain is already registered and ACS is imported")
          participantAdminConnection.connectDomain(synchronizerAlias)
      }
  }

  private def importAcs(acs: Seq[ByteString], legacyAcsImport: Boolean)(implicit
      tc: TraceContext
  ) = {
    if (legacyAcsImport) {
      participantAdminConnection.uploadAcsSnapshotLegacy(
        acs
      )
    } else {
      participantAdminConnection.uploadAcsSnapshot(
        acs
      )
    }
  }

  private def importDars(dars: Seq[Dar])(implicit tc: TraceContext) = {
    val packages = dars
      .map { dar =>
        UploadablePackage.fromByteString(dar.mainPackageId, dar.content)
      }
    participantAdminConnection.uploadDarFiles(
      packages,
      RetryFor.WaitingOnInitDependency,
    )
  }

}
