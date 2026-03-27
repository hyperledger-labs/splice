// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  SequencerAdminConnection,
}
import org.lfdecentralizedtrust.splice.identities.NodeIdentitiesStore

import java.nio.file.Path
import scala.concurrent.{ExecutionContext, Future}

class LsuStateExporter(
    lsuStatePath: Path,
    sequencerAdminConnection: SequencerAdminConnection,
    mediatorAdminConnection: MediatorAdminConnection,
    val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  private val sequencerIdentityStore =
    new NodeIdentitiesStore(sequencerAdminConnection, None, loggerFactory)
  private val mediatorIdentityStore =
    new NodeIdentitiesStore(mediatorAdminConnection, None, loggerFactory)

  def exportLSUState(upgradesAt: CantonTimestamp)(implicit tc: TraceContext): Future[LsuState] = {
    logger.info(s"Exporting LSU state for upgrade at $upgradesAt")
    for {
      _ <- sequencerAdminConnection.getLsuState(lsuStatePath)
      sequencerIdentityDump <- sequencerIdentityStore.getNodeIdentitiesDump()
      mediatorIdentityDump <- mediatorIdentityStore.getNodeIdentitiesDump()
    } yield {
      logger.info(s"Finished exporting LSU state for upgrade at $upgradesAt")
      LsuState(
        upgradesAt.toInstant,
        SynchronizerNodeIdentities(
          sequencerIdentityDump,
          mediatorIdentityDump,
        ),
        lsuStatePath,
      )
    }

  }

}
