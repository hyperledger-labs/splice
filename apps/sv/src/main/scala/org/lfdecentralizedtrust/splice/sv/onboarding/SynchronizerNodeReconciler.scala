// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  LegacySequencerConfig,
  MediatorConfig,
  ScanConfig,
  SequencerConfig,
  SynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.sv.SynchronizerNode
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeState
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.sv.util.SvUtil.{LocalMediatorConfig, LocalSequencerConfig}
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*
import com.digitalasset.canton.logging.TracedLogger
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.sv.config.SvScanConfig

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.MapHasAsScala
import scala.jdk.OptionConverters.{RichOption, RichOptional}

class SynchronizerNodeReconciler(
    dsoStore: SvDsoStore,
    connection: SpliceLedgerConnection,
    legacyMigrationId: Option[Long],
    clock: Clock,
    retryProvider: RetryProvider,
    logger: TracedLogger,
) {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  def reconcileSynchronizerNodeConfigIfRequired(
      synchronizerNode: Option[SynchronizerNode],
      synchronizerId: SynchronizerId,
      state: SynchronizerNodeState,
      migrationId: Long,
      scanConfig: SvScanConfig,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    def setConfigIfRequired() = for {
      localSequencerConfig <- SvUtil.getSequencerConfig(synchronizerNode, migrationId)
      localMediatorConfig <- SvUtil.getMediatorConfig(synchronizerNode)
      localScanConfig = java.util.Optional.of(new ScanConfig(scanConfig.publicUrl.toString()))
      rulesAndState <- dsoStore.getDsoRulesWithSvNodeState(svParty)
      nodeState = rulesAndState.svNodeState.payload
      // TODO(DACH-NY/canton-network-node#4901): do not use default, but reconcile all configured domains
      synchronizerNodeConfig = nodeState.state.synchronizerNodes.asScala
        .get(synchronizerId.toProtoPrimitive)
      sequencerConfig = synchronizerNodeConfig.flatMap(_.sequencer.toScala)
      mediatorConfig = synchronizerNodeConfig.flatMap(_.mediator.toScala)
      existingScanConfig = synchronizerNodeConfig.flatMap(_.scan.toScala).toJava
      existingSequencerConfig = sequencerConfig.map(c =>
        LocalSequencerConfig(c.sequencerId, c.url, c.migrationId)
      )
      existingMediatorConfig = mediatorConfig.map(c => LocalMediatorConfig(c.mediatorId))
      existingLegacySequencerConfig = synchronizerNodeConfig.flatMap(
        _.legacySequencerConfig.toScala
      )
      shouldMarkSequencerAsOnboarded = state match {
        case SynchronizerNodeState.OnboardedAfterDelay |
            SynchronizerNodeState.OnboardedImmediately =>
          sequencerConfig.exists(_.availableAfter.isEmpty)
        case SynchronizerNodeState.Onboarding =>
          false
      }
      updatedSequencerConfigUpdate =
        updateLegacySequencerConfig(
          existingLegacySequencerConfig,
          existingSequencerConfig,
          legacyMigrationId,
        )
      _ = ensureSequencerUrlIsDifferentWhenSynchronizerUpgraded(
        existingSequencerConfig,
        localSequencerConfig,
      )
      _ <-
        if (
          existingSequencerConfig != localSequencerConfig ||
          existingMediatorConfig != localMediatorConfig ||
          existingScanConfig != localScanConfig ||
          shouldMarkSequencerAsOnboarded ||
          updatedSequencerConfigUpdate.isRight
        ) {
          def setConfig(
              synchronizerId: SynchronizerId,
              rulesAndState: DsoRulesWithSvNodeState,
              nodeConfig: SynchronizerNodeConfig,
          )(implicit tc: TraceContext) = {
            logger.info(show"Setting domain node config to $nodeConfig")
            val cmd = rulesAndState.dsoRules.exercise(
              _.exerciseDsoRules_SetSynchronizerNodeConfig(
                svParty.toProtoPrimitive,
                synchronizerId.toProtoPrimitive,
                nodeConfig,
                rulesAndState.svNodeState.contractId,
              )
            )
            connection
              .submit(Seq(svParty), Seq(dsoParty), cmd)
              .noDedup
              .yieldResult()
          }

          val nodeConfig = new SynchronizerNodeConfig(
            synchronizerNodeConfig.map(_.cometBft).getOrElse(SvUtil.emptyCometBftConfig),
            localSequencerConfig.map { c =>
              val sequencerAvailabilityDelay =
                synchronizerNode
                  .map(_.sequencerAvailabilityDelay)
                  .getOrElse(
                    sys.error(
                      "synchronizerNode is not expected to be empty."
                    )
                  )
              new SequencerConfig(
                c.migrationId,
                c.sequencerId,
                c.url,
                (state match {
                  case SynchronizerNodeState.OnboardedAfterDelay =>
                    Some(clock.now.toInstant.plus(sequencerAvailabilityDelay))
                  case SynchronizerNodeState.OnboardedImmediately =>
                    Some(clock.now.toInstant)
                  case SynchronizerNodeState.Onboarding =>
                    None
                }).toJava,
              )
            }.toJava,
            localMediatorConfig
              .map(c =>
                new MediatorConfig(
                  c.mediatorId
                )
              )
              .toJava,
            localScanConfig,
            updatedSequencerConfigUpdate.getOrElse(existingLegacySequencerConfig).toJava,
          )
          setConfig(synchronizerId, rulesAndState, nodeConfig)
        } else {
          logger.info(s"Not setting domain node config because it is the same as the existing one.")
          Future.unit
        }
    } yield ()

    retryProvider
      .retry(
        RetryFor.WaitingOnInitDependency,
        "set_domain_config",
        s"setting domain config for $svParty",
        setConfigIfRequired(),
        logger,
      )
  }

  private def ensureSequencerUrlIsDifferentWhenSynchronizerUpgraded(
      existingSequencerConfigOpt: Option[LocalSequencerConfig],
      sequencerConfigOpt: Option[LocalSequencerConfig],
  ): Unit = {
    if (
      existingSequencerConfigOpt.exists { existingSequencerConfig =>
        sequencerConfigOpt.exists(sequencerConfig =>
          existingSequencerConfig.migrationId != sequencerConfig.migrationId &&
            existingSequencerConfig.url == sequencerConfig.url
        )
      }
    )
      sys.error("Sequencer URL must be different when domain is upgraded.")
  }
  private def updateLegacySequencerConfig(
      existingLegacySequencerConfig: Option[LegacySequencerConfig],
      existingSequencerConfig: Option[LocalSequencerConfig],
      legacyMigrationId: Option[Long],
  )(implicit
      tc: TraceContext
  ): Either[Unit, Option[LegacySequencerConfig]] = legacyMigrationId match {
    case Some(expectedLegacyMigrationId) =>
      existingSequencerConfig match {
        case Some(existingConfig) if expectedLegacyMigrationId == existingConfig.migrationId =>
          val legacySequencerConfig =
            new LegacySequencerConfig(
              expectedLegacyMigrationId,
              existingConfig.sequencerId,
              existingConfig.url,
            )
          if (!existingLegacySequencerConfig.contains(legacySequencerConfig))
            logger.info(
              s"overwriting existing legacy sequencer config $existingLegacySequencerConfig with $legacySequencerConfig for migration id $expectedLegacyMigrationId"
            )
          Right(Some(legacySequencerConfig))
        case _ =>
          if (existingLegacySequencerConfig.exists(_.migrationId != expectedLegacyMigrationId))
            logger.warn(
              s"existing legacy sequencer config with migration id is not the same as expected $expectedLegacyMigrationId"
            )
          Left(())
      }
    case None if existingLegacySequencerConfig.isDefined =>
      logger.info(
        s"removing existing legacy sequencer config as there is no expected legacy migration id"
      )
      Right(None)
    case _ =>
      Left(())
  }
}

object SynchronizerNodeReconciler {

  sealed trait SynchronizerNodeState

  object SynchronizerNodeState {

    /** Onboard after onboarding delay to ensure that the sequencer will not produce tombstones for inflight requests.
      * This is used for sequencers added to an already functional synchronizer.
      */
    case object OnboardedAfterDelay extends SynchronizerNodeState

    /** Onboard immediately, this is used after soft domain migrations where sequencers can be immediately used.
      */
    case object OnboardedImmediately extends SynchronizerNodeState
    case object Onboarding extends SynchronizerNodeState

  }

}
