package com.daml.network.environment

import com.digitalasset.canton.admin.api.client.commands.StatusAdminCommands
import com.digitalasset.canton.health.admin.data.{NodeStatus, SequencerNodeStatus}
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the subset of the Canton sequencer admin API that we rely
  * on in our own applications.
  */
class SequencerAdminConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(
      config,
      timeouts,
      loggerFactory,
      // The version endpoint is only injected into our own apps so we cannot run this against the admin API.
      enableVersionCompatCheck = false,
    ) {

  override val serviceName = "Canton Sequencer Admin API"

  private val sequencerStatusCommand =
    new StatusAdminCommands.GetStatus(SequencerNodeStatus.fromProtoV0)

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[SequencerNodeStatus]] =
    runCmd(
      sequencerStatusCommand
    )
}
