package com.daml.network.svc.automation

import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.svc.store.SvcStore
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeUnclaimedRewardsTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    val threshold: Long =
      10 // TODO(M3-46): make this the actual threshold read from the svcRules config

    store
      .lookupCoinRules()
      .flatMap({
        case None =>
          Future.successful(false)
        case Some(rules) =>
          for {
            // Fetch up to two times the threshold of contracts for increased merging throughput.
            unclaimedRewards <- store.listUnclaimedRewards(threshold * 2)
            domainId <- store.domains.getUniqueDomainId()
            res <- (unclaimedRewards.length > threshold) match {
              case false =>
                Future(None)
              case true =>
                val arg = new coinCodegen.CoinRules_MergeUnclaimedRewards(
                  unclaimedRewards.map(co => co.contractId).asJava
                )
                val cmd = rules.contractId.exerciseCoinRules_MergeUnclaimedRewards(arg)
                connection
                  .submitWithResultAndOffsetNoDedup(Seq(store.svcParty), Seq.empty, cmd, domainId)
                  .flatMap {
                    // make sure the store ingested our update so we don't
                    // attempt to merge the same reward twice
                    case (offset, outcome) =>
                      store.acs.signalWhenIngested(offset).map(_ => Some(outcome))
                  }
            }
          } yield {
            res
              .map(cid =>
                logger
                  .info(s"Merged unclaimed rewards into contract ${cid.exerciseResult.contractId}")
              )
              .isDefined
          }
      })
  }
}
