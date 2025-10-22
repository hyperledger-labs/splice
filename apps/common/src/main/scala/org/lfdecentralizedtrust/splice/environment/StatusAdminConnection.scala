// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.admin.api.client.data.NodeStatus
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContextExecutor, Future}

trait StatusAdminConnection {
  this: AppConnection & RetryProvider.Has =>
  protected implicit val ec: ExecutionContextExecutor
  type Status <: NodeStatus.Status
  protected def getStatusRequest: GrpcAdminCommand[_, _, NodeStatus[Status]]

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[Status]] =
    retryProvider.retryForClientCalls(
      "status",
      "Get node status",
      runCmd(
        getStatusRequest
      ),
      logger,
    )

}
