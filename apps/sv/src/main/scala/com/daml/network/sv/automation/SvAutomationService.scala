package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.CoinAppAutomationService
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.{SvSvStore, SvSvcStore}
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvAutomationService(
    clock: Clock,
    config: LocalSvAppConfig,
    svStore: SvSvStore,
    svcStore: SvSvcStore,
    ledgerClient: CoinLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CoinAppAutomationService(
      config.automation,
      clock,
      Map(svStore.key.svParty -> svStore, svcStore.key.svcParty -> svcStore),
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {

  registerTrigger(new ExpiredCoinTrigger(triggerContext, svcStore, connection))
  registerTrigger(new ExpiredLockedCoinTrigger(triggerContext, svcStore, connection))
  registerTrigger(new SvcRewardTrigger(triggerContext, svcStore, connection))
  registerTrigger(new SvRewardTrigger(triggerContext, svcStore, connection))
}
