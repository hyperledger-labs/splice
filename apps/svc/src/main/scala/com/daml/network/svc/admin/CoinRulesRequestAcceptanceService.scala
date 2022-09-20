package com.daml.network.svc.admin

import com.daml.ledger.api.v1.transaction.Transaction
import com.daml.ledger.client.binding.Primitive
import com.daml.network.admin.LedgerAutomationService
import com.daml.network.codegen.CC.CoinRules.CoinRulesRequest
import com.daml.network.codegen.CC.Round.{IssuingMiningRound, OpenMiningRound}
import com.daml.network.environment.CoinLedgerConnection
import com.digitalasset.canton.lifecycle.Lifecycle
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.DecodeUtil
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext

import scala.concurrent.{ExecutionContext, Future}

// TODO(i360): requests from before this service was created are missed.
class CoinRulesRequestAcceptanceService(
    svcParty: PartyId,
    connection: CoinLedgerConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends LedgerAutomationService
    with NamedLogging {

  override def templateIds: Seq[Primitive.TemplateId[_]] = Seq(CoinRulesRequest.id)

  // TODO(M1-90): This should not run concurrently with round management commands.
  // Both operations are non-atomic read-modify-write operations on the set of mining rounds.
  override def processTransaction(tx: Transaction)(implicit
      traceContext: TraceContext
  ): Future[Unit] =
    for {
      requestCids <- Future(DecodeUtil.decodeAllCreated(CoinRulesRequest)(tx).map(_.contractId))
      // TODO(i359): This will work as intended until we automatically open and close rounds
      (openMiningRounds, issuingMiningRounds) <- connection
        .activeContracts(filter =
          CoinLedgerConnection.transactionFilterByParty(
            Map(svcParty -> Seq(OpenMiningRound.id, IssuingMiningRound.id))
          )
        )
        .map { case events =>
          val openMiningRounds = events
            .flatMap(DecodeUtil.decodeCreated(OpenMiningRound))
            .filter(c => c.value.obs == svcParty.toPrim)
            .map(_.value)
          val issuingMiningRounds = events
            .flatMap(DecodeUtil.decodeCreated(IssuingMiningRound))
            .filter(c => c.value.obs == svcParty.toPrim)
            .map(_.value)
          (openMiningRounds, issuingMiningRounds)
        }
      _ <- Future.sequence(
        requestCids
          .map(cid =>
            connection
              .submitCommand(
                actAs = Seq(svcParty),
                readAs = Seq.empty,
                command = Seq(
                  cid
                    .exerciseAccept(openMiningRounds, issuingMiningRounds)
                    .command
                ),
              )
              .recoverWith { case e =>
                logger.warn(s"Failed to accept coin rules request: $e")

                // Note: we are potentially accepting multiple requests, don't fail the whole call if one of them fails.
                // No other workflow is using CoinRulesRequest contracts, it is safe to blindly retry
                // exercising the (consuming) Accept choice until it succeeds.
                Future.successful(())
              }
          )
      )
    } yield ()

  override def close(): Unit = Lifecycle.close(connection)(logger)

}
