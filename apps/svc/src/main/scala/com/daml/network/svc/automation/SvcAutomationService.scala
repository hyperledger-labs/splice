package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.CC
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContextExecutor, Future}

class SvcAutomationService(
    store: SvcStore,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(retryProvider) {
  import com.daml.network.store.AcsStore.QueryResult

  private val connection = ledgerClient.connection(this.getClass.getSimpleName)

  registerService(
    new AcsIngestionService(
      store.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      loggerFactory,
      timeouts,
    )
  )

  registerRequestHandler("handleCoinRulesRequest", store.streamCoinRulesRequests())(
    req => req.toString,
    (req, traceContext) => {
      implicit val tc: TraceContext = traceContext
      // Guard the action by a lookup for the SVC's own CoinRules to ensure that all of the dependent state
      // has already been created.
      store.lookupCoinRulesForValidator(store.svcParty).map(_.value).flatMap {
        case None =>
          // SCV setup is not yet complete: throw a StatusRuntimeException as that properly triggers the retry loop
          Future.failed(
            new StatusRuntimeException(
              Status.NOT_FOUND
                .withDescription(s"Could not find CoinRules for SVC party ${store.svcParty}")
            )
          )

        case Some(_) =>
          // SCV setup is complete: check whether the CoinRules for the requesting validator already exist
          val validatorParty = PartyId.tryFromPrim(req.payload.user)
          store.lookupCoinRulesForValidator(validatorParty).map(_.value).flatMap {
            case Some(_) =>
              // They do: reject
              val cmd = req.contractId.exerciseCoinRulesRequest_Reject().command
              logger.warn(s"Rejecting duplicate CoinRulesRequest from $validatorParty")
              connection
                .submitCommand(Seq(store.svcParty), Seq(), Seq(cmd))
                .map(_ => "rejected request for already existing rules")

            case None =>
              // They don't: accept
              val svc = store.svcParty.toPrim
              for {
                // NOTE: this is NOT SAFE under concurrent changes to the XXXMiningRounds contracts
                // That is OK here, as we assume that on-boarding of validators happens before.
                QueryResult(_, openMiningRounds0) <- store.listContracts(CC.Round.OpenMiningRound)
                QueryResult(_, issuingMiningRounds0) <- store
                  .listContracts(CC.Round.IssuingMiningRound)
                // Filter to only the ones issued for the svc itself and use them as a template
                openMiningRounds = openMiningRounds0
                  .filter(co => co.payload.obs == svc)
                  .map(_.payload)
                issuingMiningRounds = issuingMiningRounds0
                  .filter(co => co.payload.obs == svc)
                  .map(_.payload)
                cmd = req.contractId
                  .exerciseCoinRulesRequest_Accept(openMiningRounds, issuingMiningRounds)
                  .command
                // NOTE: not caring about command-dedup here, as we're going to replace this code with explicit disclosure
                _ <- connection.submitCommand(Seq(store.svcParty), Seq(), Seq(cmd))
              } yield s"accepted coin rules request from $validatorParty"
          }
      }
    },
  )

}
