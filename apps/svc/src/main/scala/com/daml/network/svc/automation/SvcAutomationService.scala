package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  AcsIngestionService,
  AutomationService,
  AuditLogIngestionService,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

class SvcAutomationService(
    store: SvcStore,
    ledgerClient: CoinLedgerClient,
    clockConfig: ClockConfig,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(clockConfig, retryProvider) {
  import com.daml.network.store.AcsStore.QueryResult

  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      store.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  registerService(
    new AuditLogIngestionService(
      "svcRoundSummaryCollectionService",
      new RoundSummaryIngestionService(
        store.svcParty,
        connection,
        store.events,
        loggerFactory,
      ),
      connection,
      loggerFactory,
      timeouts,
    )
  )

  registerRequestHandler("handleCoinRulesRequest", store.streamCoinRulesRequests())(req => {
    implicit traceContext =>
      {
        // Guard the action by a lookup for the SVC's own CoinRules to ensure that all of the dependent state
        // has already been created.
        store.lookupCoinRules().map(_.value).flatMap {
          case None =>
            // SCV setup is not yet complete: throw a StatusRuntimeException as that properly triggers the retry loop
            Future.failed(
              new StatusRuntimeException(
                Status.NOT_FOUND
                  .withDescription(s"Could not find CoinRules for SVC party ${store.svcParty}")
              )
            )

          // SCV setup is complete: accept the CoinRules request, and rely on its idempotence wrt duplicate observers
          case Some(_) =>
            val validatorParty = PartyId.tryFromProtoPrimitive(req.payload.user)
            for {
              // NOTE: this is NOT SAFE under concurrent changes to the XXXMiningRounds contracts
              // That is OK here, as we assume that on-boarding of validators happens before.
              // TODO(M3-90): make this safe under concurrent round management and onboarding
              QueryResult(_, openMiningRounds) <- store
                .listContracts(cc.round.OpenMiningRound.COMPANION)
              QueryResult(_, issuingMiningRounds) <- store
                .listContracts(cc.round.IssuingMiningRound.COMPANION)
              QueryResult(_, coinRules) <- store.getCoinRules()
              cmds = req.contractId
                .exerciseCoinRulesRequest_Accept(
                  coinRules.contractId,
                  openMiningRounds.map(_.contractId).asJava,
                  issuingMiningRounds.map(_.contractId).asJava,
                )
                .commands
                .asScala
                .toSeq
              // No command-dedup required, as the CoinRules contract is archived and recreated
              _ <- connection.submitCommands(Seq(store.svcParty), Seq(), cmds)
            } yield s"accepted coin rules request from $validatorParty"
        }
      }
  })
}
