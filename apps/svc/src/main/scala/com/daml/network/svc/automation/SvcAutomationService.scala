package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{AcsIngestionService, AutomationService}
import com.daml.network.codegen.CC
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.svc.store.SvcStore
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
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

          // SCV setup is complete: check whether CoinRules already contains the requesting validator is already in the list of observers
          case Some(coinRulesContract) =>
            val validatorParty = PartyId.tryFromPrim(req.payload.user)
            if (coinRulesContract.payload.observers.contains(validatorParty.toPrim)) {
              // They are: reject
              val cmd = req.contractId.exerciseCoinRulesRequest_Reject().command
              logger.warn(s"Rejecting duplicate CoinRulesRequest from $validatorParty")
              connection
                .submitCommand(Seq(store.svcParty), Seq(), Seq(cmd))
                .map(_ => "rejected request for already existing rules")
            } else {
              // They are not: accept
              for {
                // NOTE: this is NOT SAFE under concurrent changes to the XXXMiningRounds contracts
                // That is OK here, as we assume that on-boarding of validators happens before.
                // TODO(M3-90): make this safe under concurrent round management and onboarding
                QueryResult(_, openMiningRounds) <- store.listContracts(CC.Round.OpenMiningRound)
                QueryResult(_, issuingMiningRounds) <- store
                  .listContracts(CC.Round.IssuingMiningRound)
                QueryResult(_, coinRules) <- store.getCoinRules()
                cmd = req.contractId
                  .exerciseCoinRulesRequest_Accept(
                    coinRules.contractId,
                    openMiningRounds.map(_.contractId),
                    issuingMiningRounds.map(_.contractId),
                  )
                  .command
                // No command-dedup required, as the CoinRules contract is archived and recreated
                _ <- connection.submitCommand(Seq(store.svcParty), Seq(), Seq(cmd))
              } yield s"accepted coin rules request from $validatorParty"
            }
        }
      }
  })
}
