package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseMediatorAdministrationCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.health.admin.data.{MediatorNodeStatus, NodeStatus}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.{SequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, MediatorId}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton mediator admin API that we rely
  * on in our own applications.
  */
class MediatorAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    clock: Clock,
)(implicit ec: ExecutionContextExecutor)
    extends TopologyAdminConnection(
      config,
      timeouts,
      loggerFactory,
      retryProvider,
      clock,
    ) {

  override val serviceName = "Canton Mediator Admin API"

  private val mediatorStatusCommand =
    new StatusAdminCommands.GetStatus(MediatorNodeStatus.fromProtoV0)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[MediatorNodeStatus]] =
    runCmd(
      mediatorStatusCommand
    )

  def getMediatorId(implicit traceContext: TraceContext): Future[MediatorId] =
    getId(true).map(MediatorId(_))

  def initialize(
      domainId: DomainId,
      domainParameters: StaticDomainParameters,
      sequencerConnection: SequencerConnection,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      EnterpriseMediatorAdministrationCommands.InitializeX(
        domainId,
        domainParameters,
        SequencerConnections.default(sequencerConnection),
      )
    )
}
