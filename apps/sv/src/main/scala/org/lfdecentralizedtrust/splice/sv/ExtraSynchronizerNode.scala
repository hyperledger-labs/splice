// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv

import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  RetryProvider,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.sv.config.SvSynchronizerNodeConfig
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.domain.config.DomainParametersConfig
import com.digitalasset.canton.lifecycle.{FlagCloseable, Lifecycle}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import io.opentelemetry.api.trace.Tracer

import java.time.Duration
import scala.concurrent.ExecutionContextExecutor

// TODO(#13301) Unify this with LocalSynchronizerNode
final class ExtraSynchronizerNode(
    override val sequencerAdminConnection: SequencerAdminConnection,
    override val mediatorAdminConnection: MediatorAdminConnection,
    val parameters: DomainParametersConfig,
    val sequencerPublicApi: ClientConfig,
    override val sequencerExternalPublicUrl: String,
    override val sequencerAvailabilityDelay: Duration,
    override val loggerFactory: NamedLoggerFactory,
    override val timeouts: ProcessingTimeout,
) extends SynchronizerNode(
      sequencerAdminConnection,
      mediatorAdminConnection,
      sequencerExternalPublicUrl,
      sequencerAvailabilityDelay,
    )
    with FlagCloseable
    with NamedLogging {

  override protected def onClosed(): Unit = {
    Lifecycle.close(sequencerAdminConnection, mediatorAdminConnection)(logger)
  }
}

object ExtraSynchronizerNode {
  def fromConfig(
      conf: SvSynchronizerNodeConfig,
      loggingConfig: ApiLoggingConfig,
      loggerFactory: NamedLoggerFactory,
      grpcClientMetrics: GrpcClientMetrics,
      retryProvider: RetryProvider,
  )(implicit ec: ExecutionContextExecutor, tracer: Tracer): ExtraSynchronizerNode = {
    val sequencerAdminConnection = new SequencerAdminConnection(
      conf.sequencer.adminApi,
      loggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )
    val mediatorAdminConnection = new MediatorAdminConnection(
      conf.mediator.adminApi,
      loggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )
    new ExtraSynchronizerNode(
      sequencerAdminConnection,
      mediatorAdminConnection,
      conf.parameters,
      conf.sequencer.internalApi,
      conf.sequencer.externalPublicApiUrl,
      conf.sequencer.sequencerAvailabilityDelay.asJava,
      loggerFactory,
      retryProvider.timeouts,
    )
  }
}
