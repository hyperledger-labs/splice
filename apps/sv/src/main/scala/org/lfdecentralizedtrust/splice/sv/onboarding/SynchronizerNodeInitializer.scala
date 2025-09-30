// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.onboarding

import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{MediatorId, SequencerId, SynchronizerId}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.RetryProvider
import org.lfdecentralizedtrust.splice.setup.NodeInitializer
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.config.SvCantonIdentifierConfig

import scala.concurrent.{ExecutionContext, Future}

case class SynchronizerNodeInitializer(
    synchronizerNode: LocalSynchronizerNode,
    clock: Clock,
    logger: NamedLoggerFactory,
    retryProvider: RetryProvider,
) {
  val sequencerInitializer = new NodeInitializer(
    synchronizerNode.sequencerAdminConnection,
    retryProvider,
    logger,
  )

  val mediatorInitializer = new NodeInitializer(
    synchronizerNode.mediatorAdminConnection,
    retryProvider,
    logger,
  )
}

object SynchronizerNodeInitializer {
  def initializeLocalCantonNodesWithNewIdentities(
      identifierConfig: SvCantonIdentifierConfig,
      synchronizerNode: LocalSynchronizerNode,
      clock: Clock,
      logger: NamedLoggerFactory,
      retryProvider: RetryProvider,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    val synchronizerNodeInitializer = SynchronizerNodeInitializer(
      synchronizerNode,
      clock,
      logger,
      retryProvider,
    )

    for {
      _ <- synchronizerNodeInitializer.sequencerInitializer.initializeWithNewIdentityIfNeeded(
        identifierConfig.sequencer,
        SequencerId.apply,
      )
      _ <- synchronizerNodeInitializer.mediatorInitializer.initializeWithNewIdentityIfNeeded(
        identifierConfig.mediator,
        MediatorId.apply,
      )
    } yield ()
  }

  def rotateLocalCantonNodesOTKIfNeeded(
      identifierConfig: SvCantonIdentifierConfig,
      synchronizerNode: LocalSynchronizerNode,
      clock: Clock,
      logger: NamedLoggerFactory,
      retryProvider: RetryProvider,
      synchronizerId: SynchronizerId,
  )(implicit tc: TraceContext, ec: ExecutionContext): Future[Unit] = {
    val synchronizerNodeInitializer = SynchronizerNodeInitializer(
      synchronizerNode,
      clock,
      logger,
      retryProvider,
    )
    for {
      _ <- synchronizerNodeInitializer.sequencerInitializer.rotateLocalCantonNodesOTKIfNeeded(
        identifierConfig.sequencer,
        SequencerId.apply,
        synchronizerId,
      )
      _ <- synchronizerNodeInitializer.mediatorInitializer.rotateLocalCantonNodesOTKIfNeeded(
        identifierConfig.mediator,
        MediatorId.apply,
        synchronizerId,
      )
    } yield ()
  }

}
