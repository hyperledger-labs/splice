package com.daml.network.wallet.automation

import cats.implicits.toTraverseOps
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.splice.wallet.install.amuletoperation.CO_MergeTransferInputs
import com.daml.network.codegen.java.splice.wallet.install.amuletoperationoutcome.{
  COO_Error,
  COO_MergeTransferInputs,
}
import com.daml.network.environment.{CNLedgerConnection, CommandPriority}
import com.daml.network.scan.admin.api.client.BftScanConnection
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.config.RequireTypes.NonNegativeLong
import com.digitalasset.canton.tracing.TraceContext
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

class CollectRewardsAndMergeAmuletsTrigger(
    override protected val context: TriggerContext,
    treasury: TreasuryService,
    scanConnection: BftScanConnection,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override def isRewardOperationTrigger: Boolean = true

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] =
    for {
      activeDomain <- scanConnection.getAmuletRulesDomain()(traceContext)
      trafficBalance <- connection.trafficBalanceService
        .traverse(_.lookupAvailableTraffic(activeDomain))
        .map(_.flatten)
      result <- treasury
        .enqueueAmuletOperation(
          new CO_MergeTransferInputs(com.daml.ledger.javaapi.data.Unit.getInstance()),
          // Make this a high-prio tx if traffic balance is too low so that coupons can be redeemed for amulets
          // that can then be used to purchase domain traffic.
          if (trafficBalance.getOrElse(NonNegativeLong.zero) == NonNegativeLong.zero)
            CommandPriority.High
          else CommandPriority.Low,
        )
        .transform {
          case Success(coo) =>
            coo match {
              case outcome: COO_MergeTransferInputs =>
                // if empty -> no work was done
                Success(!outcome.optionalValue.isEmpty)
              case error: COO_Error =>
                logger.debug(s"received an unexpected COOError: $error - ignoring for now")
                // given the error, don't retry immediately
                Success(false)
              case otherwise => sys.error(s"unexpected COO return type: $otherwise")
            }
          case Failure(ex: StatusRuntimeException)
              if ex.getStatus.getCode == Status.Code.UNAVAILABLE =>
            logger.debug("Skipping amulet merge because treasury service is shutting down")
            // given the error, don't retry immediately
            Success(false)
          case Failure(err) => Failure(err)
        }
    } yield result
}
