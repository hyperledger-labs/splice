// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import com.digitalasset.canton.SynchronizerAlias
import com.digitalasset.canton.admin.api.client.data.SubmissionRequestAmplification
import com.digitalasset.canton.caching.ScaffeineCache
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.*
import org.lfdecentralizedtrust.splice.sv.config.{SvCometBftConfig, SvSequencerConfig}
import com.github.blemale.scaffeine.Scaffeine

import io.grpc.Status
import java.time.Duration
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

abstract class SynchronizerNode(
    val sequencerAdminConnection: SequencerAdminConnection,
    val mediatorAdminConnection: MediatorAdminConnection,
    val sequencerExternalPublicUrl: String,
    val sequencerAvailabilityDelay: Duration,
    val sequencerConfig: SequencerConfig,
    val mediatorSequencerAmplification: SubmissionRequestAmplification,
) {}

object SynchronizerNode {
  case class LocalSynchronizerNodes(
      current: LocalSynchronizerNode,
      successor: Option[LocalSynchronizerNode],
  )
}

class SynchronizerNodeService(
  nodes: SynchronizerNode.LocalSynchronizerNodes,
  participantAdminConnection: ParticipantAdminConnection,
  globalSynchronizerAlias: SynchronizerAlias,
    override protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext) extends NamedLogging {

  private val successorActiveRef = new java.util.concurrent.atomic.AtomicReference(false)

  val successorActiveCache =
    ScaffeineCache.buildTracedAsync[Future, Unit, Boolean](
      Scaffeine().expireAfterWrite(30.seconds),
      implicit tc => _ => successorActiveUncached(),
    )(logger, "successorActive")

  private def successorActive()(implicit tc: TraceContext): Future[Boolean] =
    if (successorActiveRef.get()) {
      Future.successful(true)
    } else {
      successorActiveCache.get(()).map {
        active =>
        if (active) {
          successorActiveRef.set(active)
        }
        active
      }
    }

  private def successorActiveUncached()(implicit tc: TraceContext): Future[Boolean] =
    nodes.successor match {
      case None => Future.successful(false)
      case Some(successor) =>
        for {
          connections <- participantAdminConnection.listConnectedDomains()
          global = connections.find(
            _.synchronizerAlias == globalSynchronizerAlias
          ).getOrElse(throw Status.NOT_FOUND.withDescription(s"No connected synchronizer with alias $globalSynchronizerAlias").asRuntimeException)
        } yield (global.physicalSynchronizerId.serial == successor.serial)
    }

  private def synchronizerNode()(implicit tc: TraceContext) =
    nodes.successor match {
      case None => Future.successful(nodes.current)
      case Some(successor) =>
        successorActive().map {
          if (_) {
            successor
          } else {
            nodes.current
          }
        }
    }

  def sequencerAdminConnection()(implicit tc: TraceContext) =
    synchronizerNode().map(_.sequencerAdminConnection)
}

sealed trait SequencerConfig {}

object SequencerConfig {
  def fromConfig(
      sequencerConfig: SvSequencerConfig,
      cometbftConfig: Option[SvCometBftConfig],
  ): SequencerConfig = {
    if (sequencerConfig.isBftSequencer) {
      BftSequencerConfig()
    } else if (cometbftConfig.exists(_.enabled)) {
      CometBftSequencerConfig()
    } else {
      ReferenceSequenceConfig()
    }
  }
}

final case class BftSequencerConfig(
) extends SequencerConfig

final case class CometBftSequencerConfig(
) extends SequencerConfig

final case class ReferenceSequenceConfig(
) extends SequencerConfig
