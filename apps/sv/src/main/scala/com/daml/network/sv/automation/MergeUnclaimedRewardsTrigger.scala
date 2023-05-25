package com.daml.network.sv.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cn.svcrules.{SvcRules, SvcRules_MergeUnclaimedRewards}
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class MergeUnclaimedRewardsTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {
  private def performWorkAsLeader(svcRules: Contract[SvcRules.ContractId, SvcRules])(implicit
      traceContext: TraceContext
  ): Future[Boolean] = {
    val threshold: Long =
      10 // TODO(M3-46): make this the actual threshold read from the svcRules config

    store
      .lookupCoinRules()
      .flatMap({
        case None =>
          Future.successful(false)
        case Some(coinRules) =>
          for {
            // Fetch up to two times the threshold of contracts for increased merging throughput.
            unclaimedRewards <- store.listUnclaimedRewards(threshold * 2)
            domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
            res <- (unclaimedRewards.length > threshold) match {
              case false =>
                Future(None)
              case true =>
                val arg = new SvcRules_MergeUnclaimedRewards(
                  coinRules.contractId,
                  unclaimedRewards.map(co => co.contractId).asJava,
                )
                val cmd = svcRules.contractId.exerciseSvcRules_MergeUnclaimedRewards(arg)
                for {
                  outcome <- connection
                    .submitWithResultNoDedup(
                      Seq(store.key.svParty),
                      Seq(store.key.svcParty),
                      cmd,
                      domainId,
                    )
                } yield Some(outcome)
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

  def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    store
      .lookupSvcRules()
      .flatMap({
        case None =>
          logger.debug("SvcRules contract not found")
          Future.successful(false)
        case Some(svcRules) =>
          store
            .svIsLeader()
            .flatMap(if (_) {
              performWorkAsLeader(svcRules)
            } else {
              Future.successful(false)
            })
      })
  }

}
