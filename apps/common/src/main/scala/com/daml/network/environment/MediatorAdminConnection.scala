package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.{
  EnterpriseMediatorAdministrationCommands,
  StatusAdminCommands,
}
import com.digitalasset.canton.config.ClientConfig
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.health.admin.data.{MediatorNodeStatus, NodeStatus}
import com.digitalasset.canton.protocol.StaticDomainParameters
import com.digitalasset.canton.sequencing.{SequencerConnection, SequencerConnections}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.{DomainId, MediatorId, NodeIdentity}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton mediator admin API that we rely
  * on in our own applications.
  */
class MediatorAdminConnection(
    config: ClientConfig,
    loggerFactory: NamedLoggerFactory,
    retryProvider: RetryProvider,
    clock: Clock,
)(implicit ec: ExecutionContextExecutor)
    extends TopologyAdminConnection(
      config,
      loggerFactory,
      retryProvider,
      clock,
    ) {

  override val serviceName = "Canton Mediator Admin API"

  private val mediatorStatusCommand =
    new StatusAdminCommands.GetStatus(MediatorNodeStatus.fromProtoV30)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[MediatorNodeStatus]] =
    runCmd(
      mediatorStatusCommand
    )

  def getMediatorId(implicit traceContext: TraceContext): Future[MediatorId] =
    getId().map(MediatorId(_))

  def initialize(
      domainId: DomainId,
      domainParameters: StaticDomainParameters,
      sequencerConnection: SequencerConnection,
  )(implicit traceContext: TraceContext): Future[Unit] =
    runCmd(
      EnterpriseMediatorAdministrationCommands.InitializeX(
        domainId,
        domainParameters,
        SequencerConnections.single(sequencerConnection),
      )
    )

  override def identity()(implicit traceContext: TraceContext): Future[NodeIdentity] = getMediatorId

  override def isNodeInitialized()(implicit traceContext: TraceContext): Future[Boolean] = {
    getStatus.map {
      case NodeStatus.Failure(_) => false
      case NodeStatus.NotInitialized(_) => false
      case NodeStatus.Success(_) => true
    }
  }

}
