// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import org.lfdecentralizedtrust.splice.admin.api.client.GrpcClientMetrics
import com.digitalasset.canton.admin.api.client.commands.{
  MediatorAdministrationCommands,
  MediatorAdminCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.admin.api.client.data.{MediatorStatus, NodeStatus}
import com.digitalasset.canton.config.{ApiLoggingConfig, ClientConfig}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.sequencing.{
  SequencerConnection,
  SequencerConnections,
  SequencerConnectionValidation,
}
import com.digitalasset.canton.topology.{DomainId, MediatorId, NodeIdentity}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton mediator admin API that we rely
  * on in our own applications.
  */
class MediatorAdminConnection(
    config: ClientConfig,
    apiLoggingConfig: ApiLoggingConfig,
    loggerFactory: NamedLoggerFactory,
    grpcClientMetrics: GrpcClientMetrics,
    retryProvider: RetryProvider,
)(implicit ec: ExecutionContextExecutor, tracer: Tracer)
    extends TopologyAdminConnection(
      config,
      apiLoggingConfig,
      loggerFactory,
      grpcClientMetrics,
      retryProvider,
    )
    with StatusAdminConnection {

  override val serviceName = "Canton Mediator Admin API"

  override protected type Status = MediatorStatus

  override protected def getStatusRequest
      : StatusAdminCommands.NodeStatusCommand[MediatorStatus, _, _] =
    MediatorAdminCommands.Health.MediatorStatusCommand()

  def getMediatorId(implicit traceContext: TraceContext): Future[MediatorId] =
    getId().map(MediatorId(_))

  def initialize(
      domainId: DomainId,
      sequencerConnection: SequencerConnection,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      MediatorAdministrationCommands.Initialize(
        domainId,
        SequencerConnections.single(sequencerConnection),
        // TODO(#10985) Consider enabling this.
        SequencerConnectionValidation.Disabled,
      )
    )

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] = getMediatorId

  override def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] = {
    getStatus.map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_, _) => false
      case NodeStatus.Success(_) => true
    }
  }

}
