// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.sv.automation.singlesv

import cats.syntax.traverse.*
import com.digitalasset.canton.config.NonNegativeDuration
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer
import org.lfdecentralizedtrust.splice.automation.{PollingTrigger, TriggerContext}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dso.svstate.SvStatus
import org.lfdecentralizedtrust.splice.environment.{
  MediatorAdminConnection,
  ParticipantAdminConnection,
  SpliceLedgerConnection,
  TopologyAdminConnection,
}
import org.lfdecentralizedtrust.splice.sv.cometbft.CometBftNode
import org.lfdecentralizedtrust.splice.sv.config.SvAppBackendConfig
import org.lfdecentralizedtrust.splice.sv.store.SvDsoStore
import org.lfdecentralizedtrust.splice.sv.util.SvUtil

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** A trigger that regularly submits the status report of the SV to the DSO. */
class SubmitSvStatusReportTrigger(
    svAppConfig: SvAppBackendConfig,
    baseContext: TriggerContext,
    store: SvDsoStore,
    ledgerApiConnection: SpliceLedgerConnection,
    cometBft: Option[CometBftNode],
    mediatorAdminConnectionO: Option[MediatorAdminConnection],
    participantAdminConnection: ParticipantAdminConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override protected def context: TriggerContext =
    baseContext.copy(config =
      baseContext.config.copy(pollingInterval = svAppConfig.onLedgerStatusReportInterval)
    )

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    val svParty = store.key.svParty
    logger.debug(s"Attempting to submit on-ledger SvStatus report...")
    for {
      dsoRules <- store.getDsoRules()
      statusReport <- store.getSvStatusReport(store.key.svParty)
      openMiningRounds <- store.getOpenMiningRoundTriple()
      cometBftHeight <- cometBft.traverse(_.getLatestBlockHeight())
      mediatorAdminConnection = SvUtil.getMediatorAdminConnection(
        mediatorAdminConnectionO
      )
      mediatorSynchronizerTimeLb <- getDomainTimeLowerBound(
        mediatorAdminConnection,
        dsoRules.domain,
      )
      participantSynchronizerTimeLb <- getDomainTimeLowerBound(
        participantAdminConnection,
        dsoRules.domain,
      )
      now = context.clock.now
      status = new SvStatus(
        now.toInstant,
        // Production deployments always define all of these values, which is why we don't embed the 'Option' value
        // into the status report. We'll only see the magic default values in our tests.
        cometBftHeight.getOrElse[Long](-1L),
        mediatorSynchronizerTimeLb,
        participantSynchronizerTimeLb,
        openMiningRounds.newest.payload.round,
      )
      cmd = dsoRules.exercise(
        _.exerciseDsoRules_SubmitStatusReport(
          svParty.toProtoPrimitive,
          statusReport.contractId,
          status,
        )
      )
      _ <- ledgerApiConnection
        .submit(Seq(svParty), Seq(store.key.dsoParty), cmd)
        .noDedup
        .yieldUnit()
      _ = logger.debug(s"Completed submitting on-ledger SvStatus report.")
    } yield false
  }

  private def getDomainTimeLowerBound(connection: TopologyAdminConnection, domain: SynchronizerId)(
      implicit tc: TraceContext
  ): Future[Instant] = {
    connection
      .getDomainTimeLowerBound(
        domain,
        maxDomainTimeLag = context.config.pollingInterval,
        timeout = SubmitSvStatusReportTrigger.DomainTimeTimeout,
      )
      .transform {
        case Success(ok) =>
          Success(ok.timestamp.toInstant)
        case Failure(ex) =>
          logger
            .info(s"Failed to get domain time lower bound from ${connection.serviceName}", ex)
          Success(Instant.EPOCH)
      }
  }
}

object SubmitSvStatusReportTrigger {
  val DomainTimeTimeout: NonNegativeDuration = NonNegativeDuration.ofSeconds(15L)
}
