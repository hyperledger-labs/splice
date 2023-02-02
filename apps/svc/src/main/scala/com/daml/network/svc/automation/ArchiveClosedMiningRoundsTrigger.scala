package com.daml.network.svc.automation

import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cc.coin.CoinRules
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.JavaContract as Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ArchiveClosedMiningRoundsTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
    waitForUnclaimedRewards: Boolean,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  def tryArchivingClosedRound(
      closedRound: Contract[ClosedMiningRound.ContractId, ClosedMiningRound],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  )(implicit traceContext: TraceContext): Future[Boolean] = {
    for {
      domainId <- store.domains.getUniqueDomainId()
      appRewardCoupons <- store.listAppRewardCoupons(closedRound.payload.round.number, Some(1))
      validatorRewardCoupons <- store
        .listValidatorRewardCoupons(closedRound.payload.round.number, Some(1))
      // TODO(M3-63) Once we are resilient to unavailable validators - always wait for unclaimed rewards
      res <-
        (waitForUnclaimedRewards && (appRewardCoupons.length + validatorRewardCoupons.length > 0)) match {
          case true =>
            logger.debug(
              s"Round ${closedRound.payload.round.number} still has unclaimed reward coupons, not archiving yet"
            )
            Future(false)
          case false => {
            connection
              .submitCommandsNoDedup(
                Seq(store.svcParty),
                Seq.empty,
                coinRules.contractId
                  .exerciseCoinRules_MiningRound_Archive(
                    closedRound.contractId
                  )
                  .commands
                  .asScala
                  .toSeq,
                domainId,
              )
              .flatMap(tx =>
                // make sure the store ingested our update so we don't
                // attempt to archive the same round twice
                store.acs.signalWhenIngested(tx.getOffset())
              )
              .map(_ => {
                logger.info(
                  s"successfully archived closed mining round ${closedRound.payload.round.number}"
                )
                true
              })
          }
        }
    } yield res
  }

  override def performWorkIfAvailable()(implicit traceContext: TraceContext): Future[Boolean] = {
    store
      .lookupCoinRules()
      .flatMap({
        case None =>
          logger.debug("CoinRules contract not found")
          Future.successful(false)
        case Some(coinRules) =>
          store
            .lookupOldestClosedMiningRound()
            .flatMap({
              case None =>
                Future.successful(false)
              case Some(closedRound) =>
                tryArchivingClosedRound(closedRound, coinRules)
            })

      })
  }

}
