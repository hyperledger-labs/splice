package com.daml.network.wallet.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.wallet.install.coinoperation.CO_MergeTransferInputs
import com.daml.network.codegen.java.cn.wallet.install.coinoperationoutcome.{
  COO_Error,
  COO_MergeTransferInputs,
}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.wallet.store.UserWalletStore
import com.daml.network.wallet.treasury.TreasuryService
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class CollectRewardsAndMergeCoinsTrigger(
    override protected val context: TriggerContext,
    store: UserWalletStore,
    treasury: TreasuryService,
    connection: CoinLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      coo <- treasury
        .enqueueCoinOperation(
          new CO_MergeTransferInputs(com.daml.ledger.javaapi.data.Unit.getInstance())
        )
      res = coo match {
        case outcome: COO_MergeTransferInputs =>
          // if empty -> no work was done
          !outcome.optionalValue.isEmpty
        case error: COO_Error =>
          logger.debug(s"received an unexpected COOError: $error - ignoring for now")
          // given the error, don't retry immediately
          false
        case otherwise => sys.error(s"unexpected COO return type: $otherwise")
      }
    } yield res
  }

}
