// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.sv.onboarding

import com.daml.network.environment.RetryProvider
import com.daml.network.setup.NodeInitializer
import com.daml.network.sv.LocalSynchronizerNode
import com.daml.network.sv.config.SvCantonIdentifierConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{MediatorId, SequencerId}
import com.digitalasset.canton.tracing.TraceContext

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
}
