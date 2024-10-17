// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.admin.api.client.commands.StatusAdminCommands
import com.digitalasset.canton.health.admin.data.NodeStatus
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.Future

trait StatusAdminConnection {
  this: AppConnection =>

  protected type Status <: NodeStatus.Status
  protected def getStatusRequest: StatusAdminCommands.GetStatus[Status]

  def getStatus(implicit traceContext: TraceContext): Future[NodeStatus[Status]] = runCmd(
    getStatusRequest
  )

}
