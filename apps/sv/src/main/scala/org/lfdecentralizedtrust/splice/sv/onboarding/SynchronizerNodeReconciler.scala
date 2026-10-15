// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import cats.implicits.{catsSyntaxApplicativeError, toTraverseOps}
import com.digitalasset.canton.config.RequireTypes.NonNegativeInt
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil
import com.digitalasset.canton.util.ShowUtil.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.decentralizedsynchronizer.{
  MediatorConfig,
  PhysicalSynchronizerNodeConfig,
  ScanConfig,
  SequencerConnectionConfig,
  SequencerIdentityConfig,
  SynchronizerNodeConfig,
}
import org.lfdecentralizedtrust.splice.environment.{RetryFor, RetryProvider, SpliceLedgerConnection}
import org.lfdecentralizedtrust.splice.environment.SynchronizerNode.LocalSynchronizerNodes
import org.lfdecentralizedtrust.splice.store.DsoRulesStore.DsoRulesWithSvNodeState
import org.lfdecentralizedtrust.splice.sv.config.SvScanConfig
import org.lfdecentralizedtrust.splice.sv.onboarding.SynchronizerNodeReconciler.SynchronizerNodeState
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.util.SvUtil
import org.lfdecentralizedtrust.splice.sv.util.SvUtil.LocalMediatorConfig
import org.lfdecentralizedtrust.splice.util.PrettyInstances.*

import java.lang
import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.{MapHasAsJava, MapHasAsScala}
import scala.jdk.OptionConverters.{RichOption, RichOptional}

class SynchronizerNodeReconciler(
    dsoStore: SvDsoStore,
    connection: SpliceLedgerConnection,
    clock: Clock,
    retryProvider: RetryProvider,
    val loggerFactory: NamedLoggerFactory,
    scanConfig: SvScanConfig,
) extends NamedLogging {

  private val svParty = dsoStore.key.svParty
  private val dsoParty = dsoStore.key.dsoParty

  def reconcileSynchronizerNodeConfigIfRequired(
      synchronizerNodes: LocalSynchronizerNodes[LocalSynchronizerNode],
      synchronizerId: SynchronizerId,
      state: SynchronizerNodeState,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Unit] = {
    val currentNode = synchronizerNodes.current
    def setConfigIfRequired() = for {
      localSequencerConfig <- SvUtil.getSequencerConfig(Some(currentNode))
      localMediatorConfig <- SvUtil.getMediatorConfig(Some(currentNode))
      localScanConfig = java.util.Optional.of(new ScanConfig(scanConfig.publicUrl.toString()))
      rulesAndState <- dsoStore.getDsoRulesWithSvNodeState(svParty)
      nodeState = rulesAndState.svNodeState.payload
      synchronizerNodeConfig = nodeState.state.synchronizerNodes.asScala
        .get(synchronizerId.toProtoPrimitive)
      existingSequencerIdentityConfig = synchronizerNodeConfig.flatMap(_.sequencerIdentity.toScala)
      mediatorConfig = synchronizerNodeConfig.flatMap(_.mediator.toScala)
      existingScanConfig = synchronizerNodeConfig.flatMap(_.scan.toScala).toJava
      existingMediatorConfig = mediatorConfig.map(c => LocalMediatorConfig(c.mediatorId))
      shouldMarkSequencerAsOnboarded = state match {
        case SynchronizerNodeState.OnboardedAfterDelay |
            SynchronizerNodeState.OnboardedImmediately =>
          existingSequencerIdentityConfig
            .flatMap(_.availableAfter.toScala)
            .isEmpty
        case SynchronizerNodeState.Onboarding(_) =>
          false
      }
      existingPhysicalSynchronizers = synchronizerNodeConfig.flatMap(
        _.physicalSynchronizers.toScala
      )
      scalaExistingPhysicalSynchronizers = existingPhysicalSynchronizers.map(_.asScala.toMap)
      localPhysicalSynchronizers <- buildPhysicalSynchronizers(
        synchronizerNodes,
        scalaExistingPhysicalSynchronizers,
        state,
      )
      sequencerIdentityChanged = existingSequencerIdentityConfig.map(
        _.sequencerId
      ) != localSequencerConfig.map(
        _.sequencerId
      )
      _ <-
        if (
          sequencerIdentityChanged ||
          existingMediatorConfig != localMediatorConfig ||
          existingScanConfig != localScanConfig ||
          shouldMarkSequencerAsOnboarded ||
          scalaExistingPhysicalSynchronizers != localPhysicalSynchronizers
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

          val sequencerAvailableAfter: Option[Instant] = localSequencerConfig.flatMap { _ =>
            val sequencerAvailabilityDelay =
              currentNode.sequencerAvailabilityDelay
            state match {
              case SynchronizerNodeState.OnboardedAfterDelay =>
                Some(clock.now.toInstant.plus(sequencerAvailabilityDelay))
              case SynchronizerNodeState.OnboardedImmediately =>
                Some(clock.now.toInstant)
              case SynchronizerNodeState.Onboarding(_) =>
                None
            }
          }

          val sequencerIdentityConfig: java.util.Optional[SequencerIdentityConfig] =
            localSequencerConfig
              .map(c =>
                new SequencerIdentityConfig(
                  c.sequencerId,
                  existingSequencerIdentityConfig
                    .flatMap(_.availableAfter.toScala)
                    .orElse(sequencerAvailableAfter)
                    .toJava,
                )
              )
              .toJava

          val nodeConfig = new SynchronizerNodeConfig(
            synchronizerNodeConfig.map(_.cometBft).getOrElse(SvUtil.emptyCometBftConfig),
            // deprecated in favor of the sequencerIdentityConfig and physicalSynchronizers
            None.toJava,
            localMediatorConfig
              .map(c =>
                new MediatorConfig(
                  c.mediatorId
                )
              )
              .toJava,
            localScanConfig,
            // deprecated legacy sequencer config
            None.toJava,
            sequencerIdentityConfig,
            localPhysicalSynchronizers
              .map(_.asJava)
              .toJava,
          )
          setConfig(synchronizerId.logical, rulesAndState, nodeConfig)
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

  private[onboarding] def buildPhysicalSynchronizers(
      synchronizerNodes: LocalSynchronizerNodes[LocalSynchronizerNode],
      existingState: Option[Map[lang.Long, PhysicalSynchronizerNodeConfig]],
      state: SynchronizerNodeState,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[Option[Map[lang.Long, PhysicalSynchronizerNodeConfig]]] = {
    val currentEntryFuture = {
      val currentNode = synchronizerNodes.current
      val serialOverride = state match {
        case SynchronizerNodeState.OnboardedAfterDelay => None
        case SynchronizerNodeState.OnboardedImmediately => None
        case SynchronizerNodeState.Onboarding(serial) => Some(serial)
      }
      buildNodeConfig(currentNode, serialOverride)
    }

    val legacyEntryFuture =
      synchronizerNodes.legacy.traverse { legacyNode =>
        buildNodeConfig(legacyNode)
      }

    val additionalLegacyEntriesFuture =
      MonadUtil.sequentialTraverse(synchronizerNodes.additionalLegacy.toList) { legacyNode =>
        buildNodeConfig(legacyNode)
      }

    val successorEntryFuture =
      synchronizerNodes.successor.flatTraverse { successorNode =>
        successorNode.sequencerAdminConnection
          .isNodeInitialized()
          .attemptT
          .foldF(
            failure =>
              currentEntryFuture.map { case (currentSyncSerial, _) =>
                val existingSuccessors =
                  existingState.map(_.view.filterKeys(_ > currentSyncSerial).toSeq)
                logger.info(
                  s"Failed to get successor status, will keep state with serial > than $currentSyncSerial: $existingSuccessors",
                  failure,
                )
                existingSuccessors
              },
            {
              case true =>
                buildNodeConfig(successorNode).map(config => Some(Seq(config)))
              case false =>
                Future.successful(None)
            },
          )
      }

    for {
      currentEntry <- currentEntryFuture
      legacyEntry <- legacyEntryFuture
      successorEntry <- successorEntryFuture
      additionalLegacyEntries <- additionalLegacyEntriesFuture
    } yield {
      val entries =
        legacyEntry.toList ++ List(
          currentEntry
        ) ++ successorEntry.toList.flatten ++ additionalLegacyEntries
      val urlToSerials = entries
        .groupMap { case (_, config) =>
          config.sequencer.toScala.map(_.url)
        } { case (serial, _) => serial }
      urlToSerials.foreach { case (url, serials) =>
        if (serials.distinct.sizeIs > 1) {
          sys.error(
            s"Different serials ${serials.distinct} are configured with the same sequencer url $url"
          )
        }
      }
      Some(entries.toMap)
    }
  }

  private def buildNodeConfig(
      node: LocalSynchronizerNode,
      serialOverride: Option[NonNegativeInt] = None,
  )(implicit
      ec: ExecutionContext,
      tc: TraceContext,
  ): Future[(lang.Long, PhysicalSynchronizerNodeConfig)] = {
    val serialFuture = serialOverride match {
      case Some(serialOverride) => Future.successful(serialOverride)
      case None => node.sequencerAdminConnection.getPhysicalSynchronizerId().map(_.serial)
    }
    for {
      serial <- serialFuture
      jSerial = lang.Long.valueOf(serial.unwrap.toLong)
    } yield {
      jSerial -> new PhysicalSynchronizerNodeConfig(
        java.util.Optional.of(
          new SequencerConnectionConfig(
            node.sequencerExternalPublicUrl
          )
        )
      )
    }
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

    /** When onboarding the sequencer doesn't know the PSid yet so we set the serial from the participant
      */
    case class Onboarding(serial: NonNegativeInt) extends SynchronizerNodeState

  }

}
