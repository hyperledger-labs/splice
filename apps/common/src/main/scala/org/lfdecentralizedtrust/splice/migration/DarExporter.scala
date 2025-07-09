// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.migration

import org.lfdecentralizedtrust.splice.environment.ParticipantAdminConnection
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.MonadUtil

import scala.concurrent.{ExecutionContext, Future}

class DarExporter(participantAdminConnection: ParticipantAdminConnection) {

  def exportAllDars()(implicit tc: TraceContext, ec: ExecutionContext): Future[Seq[Dar]] = for {
    darDescriptions <- participantAdminConnection.listDars()
    dars <- MonadUtil.sequentialTraverse(darDescriptions) { dar =>
      participantAdminConnection.lookupDar(dar.mainPackageId).map(_.map(Dar(dar.mainPackageId, _)))
    }
  } yield dars.flatten

}
