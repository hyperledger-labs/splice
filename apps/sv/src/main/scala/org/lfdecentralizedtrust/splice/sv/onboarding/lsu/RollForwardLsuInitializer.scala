// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding.lsu

import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{PhysicalSynchronizerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.Status
import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.environment.{
  ParticipantAdminConnection,
  RetryFor,
  RetryProvider,
  SpliceLedgerClient,
  SynchronizerNodeService,
}
import org.lfdecentralizedtrust.splice.store.{
  DomainTimeSynchronization,
  DomainUnpausedSynchronization,
}
import org.lfdecentralizedtrust.splice.sv.automation.{SvDsoAutomationService, SvSvAutomationService}
import org.lfdecentralizedtrust.splice.sv.config.{SvAppBackendConfig, SvOnboardingConfig}
import org.lfdecentralizedtrust.splice.sv.lsu.{LsuNodeInitializer, LsuStateExporter}
import org.lfdecentralizedtrust.splice.sv.onboarding.{DsoPartyHosting, NodeInitializerUtil}
import org.lfdecentralizedtrust.splice.sv.onboarding.joining.JoiningNodeInitializer
import org.lfdecentralizedtrust.splice.sv.store.{SvDsoStore, SvSvStore}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode

import scala.concurrent.{ExecutionContextExecutor, Future}

class RollForwardLsuInitializer(
    synchronizerNodeService: SynchronizerNodeService[LocalSynchronizerNode],
    override protected val config: SvAppBackendConfig,
    override protected val ledgerClient: SpliceLedgerClient,
    override protected val participantAdminConnection: ParticipantAdminConnection,
    override protected val clock: Clock,
    override protected val domainTimeSync: DomainTimeSynchronization,
    override protected val domainUnpausedSync: DomainUnpausedSynchronization,
    override protected val storage: DbStorage,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val retryProvider: RetryProvider,
    override protected val spliceInstanceNamesConfig: SpliceInstanceNamesConfig,
    newJoiningNodeInitializer: Option[SvOnboardingConfig.JoinWithKey] => JoiningNodeInitializer,
    rollForwardConfig: SvOnboardingConfig.RollForwardLsu,
)(implicit
    ec: ExecutionContextExecutor
) extends NodeInitializerUtil {

  val legacyNode = synchronizerNodeService.nodes.legacy.getOrElse(
    throw new IllegalArgumentException(s"Legacy node must be set for roll-forward LSU")
  )
  val currentNode = synchronizerNodeService.nodes.current

  val exporter = new LsuStateExporter(
    config.domainMigrationDumpPath.getOrElse(
      throw new IllegalArgumentException("Domain migration dump path must be set for LSU")
    ),
    legacyNode.sequencerAdminConnection,
    legacyNode.mediatorAdminConnection,
    loggerFactory,
  )

  val initializer = new LsuNodeInitializer(
    synchronizerNodeService.nodes,
    currentNode, // roll forward goes from legacy => current
    loggerFactory,
    retryProvider,
  )

  def rollForward()(implicit tc: TraceContext): Future[
    (
        SynchronizerId,
        DsoPartyHosting,
        SvSvStore,
        SvSvAutomationService,
        SvDsoStore,
        SvDsoAutomationService,
    )
  ] =
    for {
      sequencerInitialized <- retryProvider.getValueWithRetries(
        RetryFor.Automation,
        "sequencer_startup",
        "New sequencer has started",
        currentNode.sequencerAdminConnection.getStatus(tc).map {
          case NodeStatus.NotInitialized(_, _) => false
          case NodeStatus.Success(_) => true
          case NodeStatus.Failure(msg) =>
            throw Status.FAILED_PRECONDITION
              .withDescription(s"Failed to get sequencer status: $msg")
              .asRuntimeException()
        },
        logger,
      )
      mediatorInitialized <- retryProvider.getValueWithRetries(
        RetryFor.Automation,
        "mediator_startup",
        "New mediator has started",
        currentNode.mediatorAdminConnection.getStatus(tc).map {
          case NodeStatus.NotInitialized(_, _) => false
          case NodeStatus.Success(_) => true
          case NodeStatus.Failure(msg) =>
            throw Status.FAILED_PRECONDITION
              .withDescription(s"Failed to get mediator status: $msg")
              .asRuntimeException()
        },
        logger,
      )
      legacyPhysicalSynchronizerId <- legacyNode.sequencerAdminConnection
        .getPhysicalSynchronizerId()
      newPhysicalSynchronizerId = PhysicalSynchronizerId(
        legacyPhysicalSynchronizerId.logical,
        rollForwardConfig.newPhysicalSynchronizerSerial,
        rollForwardConfig.newPhysicalSynchronizerProtocolVersion,
      )
      (topologyExportTime, trafficExportTime, upgradeTime) <- rollForwardConfig.exportTimes match {
        case Some(config) =>
          val resolved = (
            config.topologyExportTime.getTimestamp(),
            config.trafficExportTime.getTimestamp(),
            config.upgradeTime.map(_.getTimestamp()),
          )
          logger.info(s"Using export timestamps from config: $config, resolved: $resolved")
          Future.successful(resolved)
        case None =>
          for {
            announcements <- legacyNode.sequencerAdminConnection.listLsuAnnouncements(
              legacyPhysicalSynchronizerId.logical
            )
          } yield {
            announcements match {
              case Seq(announcement) =>
                logger.info(s"Using export timestamps from announcement: $announcement")
                (
                  CantonTimestamp.assertFromInstant(announcement.base.validFrom),
                  announcement.mapping.upgradeTime,
                  Some(announcement.mapping.upgradeTime),
                )
              case _ =>
                throw new IllegalStateException(
                  s"Expected exactly one LSU announcement but got: $announcements"
                )
            }
          }

      }
      _ <-
        if (sequencerInitialized && mediatorInitialized) {
          logger.info("Sequencer and mediator are already initialized")
          Future.unit
        } else {
          for {
            state <- exporter.exportLSUState(
              topologyExportTime =
                rollForwardConfig.exportTimes.map(_.topologyExportTime.getTimestamp())
            )
            _ <- initializer.initializeSynchronizer(
              state,
              newPhysicalSynchronizerId,
              now = clock.now,
              // Upgrade time is used to publish the sequencer sucessor which we don't care about for roll-forward LSUs.
              upgradeTime = None,
            )
            trafficState <- legacyNode.sequencerAdminConnection.getLsuTrafficControlState(ts =
              rollForwardConfig.exportTimes.map(_.trafficExportTime.getTimestamp())
            )
            _ <- currentNode.sequencerAdminConnection.setLsuTrafficControlState(trafficState)
          } yield ()
        }
      sequencerId <- currentNode.sequencerAdminConnection.getSequencerId
      participantPhysicalSynchronizerId <- participantAdminConnection.getPhysicalSynchronizerId(
        legacyPhysicalSynchronizerId.logical
      )
      _ <-
        if (participantPhysicalSynchronizerId == newPhysicalSynchronizerId) {
          logger.info("Participant already migrated")
          Future.unit
        } else {
          logger.info(
            s"Participant is on physical synchronizer id $participantPhysicalSynchronizerId, initiating manual LSU"
          )
          participantAdminConnection
            .performManualLsu(
              legacyPhysicalSynchronizerId,
              newPhysicalSynchronizerId,
              upgradeTime,
              Map(
                sequencerId -> initializer.successorConnection
              ),
            )
            .map { r =>
              logger.info("Manual LSU completed")
              r
            }
        }
      result <- newJoiningNodeInitializer(None).joinDsoAndOnboardNodes()
    } yield result
}
