// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection

import scala.concurrent.{ExecutionContext, Future}

trait SyncConnectionStalenessCheck {

  def participantAdminConnection: ParticipantAdminConnection

  def isNotConnectedToSync()(implicit tc: TraceContext, ec: ExecutionContext): Future[Boolean] =
    participantAdminConnection.listConnectedDomains().map(_.isEmpty)
}
