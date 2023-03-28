package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.admin.api.client.ParticipantAdminConnection
import com.daml.network.automation.CNNodeAppAutomationService
import com.daml.network.environment.{CNLedgerClient, RetryProvider}
import com.daml.network.sv.config.LocalSvAppConfig
import com.daml.network.sv.store.SvSvStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.ExecutionContextExecutor

class SvSvAutomationService(
    clock: Clock,
    config: LocalSvAppConfig,
    svStore: SvSvStore,
    ledgerClient: CNLedgerClient,
    participantAdminConnection: ParticipantAdminConnection,
    retryProvider: RetryProvider,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends CNNodeAppAutomationService(
      config.automation,
      clock,
      Map(svStore.key.svParty -> svStore),
      ledgerClient,
      participantAdminConnection,
      retryProvider,
    ) {}
