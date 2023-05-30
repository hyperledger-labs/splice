package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.StatusAdminCommands
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.health.admin.data.{NodeStatus, MediatorNodeStatus}
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton mediator admin API that we rely
  * on in our own applications.
  */
class MediatorAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(
      config,
      timeouts,
      loggerFactory,
      // The version endpoint is only injected into our own apps so we cannot run this against the admin .
      enableVersionCompatCheck = false,
    ) {

  override val serviceName = "Canton Mediator Admin API"

  private val mediatorStatusCommand =
    new StatusAdminCommands.GetStatus(MediatorNodeStatus.fromProtoV0)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[MediatorNodeStatus]] =
    runCmd(
      mediatorStatusCommand
    )
}
