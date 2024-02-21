package com.daml.network.sv.automation.singlesv

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.svc.svstatus.SvStatus
import com.daml.network.environment.{
  CNLedgerConnection,
  MediatorAdminConnection,
  ParticipantAdminConnection,
}
import com.daml.network.sv.cometbft.CometBftNode
import com.daml.network.sv.store.SvSvcStore
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import cats.syntax.traverse.*
import com.daml.network.sv.config.SvAppBackendConfig
import com.digitalasset.canton.data.CantonTimestamp

/** A trigger that regularly submits the status report of the SV to the SVC. */
class SubmitSvStatusReportTrigger(
    svAppConfig: SvAppBackendConfig,
    baseContext: TriggerContext,
    store: SvSvcStore,
    ledgerApiConnection: CNLedgerConnection,
    cometBft: Option[CometBftNode],
    mediatorAdminConnection: Option[MediatorAdminConnection],
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
      svcRules <- store.getSvcRules()
      statusReport <- store.getSvStatusReport(store.key.svParty)
      openMiningRounds <- store.getOpenMiningRoundTriple()
      cometBftHeight <- cometBft.traverse(_.getLatestBlockHeight())
      mediatorDomainTime <- mediatorAdminConnection.traverse(
        // TODO(#10189): only request a time-proof more recent than the status report interval
        _.getDomainTime(svcRules.domain, timeouts.shutdownShort)
      )
      participantDomainTime <- participantAdminConnection.getDomainTime(
        svcRules.domain,
        timeouts.shutdownShort,
      )
      now = context.clock.now
      status = new SvStatus(
        now.toInstant,
        // Production deployments always define all of these values, which is why we don't embed the 'Option' value
        // into the status report. We'll only see the magic default values in our tests.
        cometBftHeight.getOrElse[Long](-1L),
        mediatorDomainTime.fold(CantonTimestamp.MinValue)(_.timestamp).toInstant,
        participantDomainTime.timestamp.toInstant,
        openMiningRounds.newest.payload.round,
      )
      cmd = svcRules.exercise(
        _.exerciseSvcRules_SubmitStatusReport(
          svParty.toProtoPrimitive,
          statusReport.contractId,
          status,
        )
      )
      _ <- ledgerApiConnection
        .submit(Seq(svParty), Seq(store.key.svcParty), cmd)
        .noDedup
        .yieldUnit()
      _ = logger.debug(s"Completed submitting on-ledger SvStatus report.")
    } yield (false)
  }
}
