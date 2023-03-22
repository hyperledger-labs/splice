package com.daml.network.svc.admin.api.client

// TODO(#2241) we can probably remove this file entirely once we have proper bootstrap in place

import com.daml.network.admin.api.client.AppConnection
import com.daml.network.svc.admin.api.client.commands.GrpcSvcAppClient
import com.digitalasset.canton.config.{ClientConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

/** Connection to the admin API of CC Svc.
  */
final class SvcConnection(
    config: ClientConfig,
    timeouts: ProcessingTimeout,
    loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContextExecutor)
    extends AppConnection(config, timeouts, loggerFactory) {

  override val serviceName = "svc"

  def joinCollective(svParty: PartyId)(implicit
      traceContext: TraceContext
  ): Future[Unit] = {
    runCmd(GrpcSvcAppClient.JoinCollective(svParty))
  }

  def getDebugInfo()(implicit
      traceContext: TraceContext
  ): Future[GrpcSvcAppClient.DebugInfo] = {
    runCmd(GrpcSvcAppClient.GetDebugInfo())
  }
}
