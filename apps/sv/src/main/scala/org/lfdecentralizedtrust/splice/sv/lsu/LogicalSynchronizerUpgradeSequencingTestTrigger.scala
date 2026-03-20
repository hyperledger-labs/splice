// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.lsu

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.MediatorGroup.MediatorGroupIndex
import com.digitalasset.canton.topology.PhysicalSynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.sv.LocalSynchronizerNode
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig

import scala.concurrent.{ExecutionContext, Future}

class LogicalSynchronizerUpgradeSequencingTestTrigger(
    svAppConfig: SvAppBackendConfig,
    baseContext: TriggerContext,
    currentSynchronizerNode: LocalSynchronizerNode,
    successorSynchronizerNode: LocalSynchronizerNode,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override protected lazy val context: TriggerContext =
    baseContext.copy(config =
      baseContext.config.copy(pollingInterval = svAppConfig.lsuSequencingTestInterval)
    )

  // We use a bare PollingTrigger here to make sure it doesn't run in a busy loop but really waits for the polling interval
  // like SubmitSvStatusReportTrigger
  override def performWorkIfAvailable()(implicit tc: TraceContext): Future[Boolean] = {
    val now = context.clock.now
    for {
      physicalSynchronizerId <- currentSynchronizerNode.sequencerAdminConnection
        .getPhysicalSynchronizerId()
      announcements <- announcements(now, physicalSynchronizerId)
      sequencerInitialized <- successorSynchronizerNode.sequencerAdminConnection
        .getStatus(tc)
        .map(_.successOption.isDefined)
      _ <-
        if (announcements.nonEmpty && sequencerInitialized) {
          logger.info("Submitting LSU sequencing test")
          successorSynchronizerNode.sequencerAdminConnection.performLsuSequencingTest(
            MediatorGroupIndex.zero
          )
        } else {
          Future.unit
        }
    } yield false
  }

  private def announcements(now: CantonTimestamp, synchronizerId: PhysicalSynchronizerId)(implicit
      tc: TraceContext
  ) = {
    currentSynchronizerNode.sequencerAdminConnection
      .listLsuAnnouncements(synchronizerId.logical)
      .map(_.filter { announcement =>
        announcement.base.validFrom
          .isBefore(now.toInstant) && now.toInstant.isBefore(
          announcement.mapping.upgradeTime.toInstant
        ) && announcement.mapping.successorSynchronizerId != synchronizerId
      })
  }
}
