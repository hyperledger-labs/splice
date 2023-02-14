package com.daml.network.svc.automation

import com.daml.network.codegen.java.cc.coin.{CoinRules}
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.automation.PollingTrigger
import com.daml.network.automation.TriggerContext
import com.daml.network.svc.store.SvcStore
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.Contract
import scala.concurrent.{Future, ExecutionContext}
import io.opentelemetry.api.trace.Tracer
import scala.jdk.CollectionConverters.*
import com.daml.ledger.javaapi.data.codegen.{Exercised, Update}
import com.daml.network.codegen.java.cc.coin.UnclaimedReward.ContractId
import java.util.Optional
import com.digitalasset.canton.tracing.TraceContext

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    store: SvcStore,
    connection: CoinLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  def getCmdsForRound(
      closedRound: Contract[ClosedMiningRound.ContractId, ClosedMiningRound],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  ): Future[Seq[Update[Exercised[Optional[ContractId]]]]] = {
    for {
      appRewards <- store.listAppRewardCouponsGroupedByCounterparty(
        closedRound.payload.round.number,
        totalCouponsLimit = Some(100),
      )
      appRewardCmds = appRewards.map(group =>
        coinRules.contractId.exerciseCoinRules_ClaimExpiredRewards(
          closedRound.contractId,
          Seq.empty.asJava,
          group.asJava,
        )
      )
      validatorRewards <- store.listValidatorRewardCouponsGroupedByCounterparty(
        closedRound.payload.round.number,
        totalCouponsLimit = Some(100),
      )
      validatorRewardCmds = validatorRewards.map(group =>
        coinRules.contractId.exerciseCoinRules_ClaimExpiredRewards(
          closedRound.contractId,
          group.asJava,
          Seq.empty.asJava,
        )
      )
    } yield appRewardCmds ++ validatorRewardCmds
  }

  def expireRewardCouponsForRound(
      closedRound: Contract[ClosedMiningRound.ContractId, ClosedMiningRound],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  )(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      domainId <- store.domains.getUniqueDomainId()
      cmds <- getCmdsForRound(closedRound, coinRules)
      acs <- store.defaultAcs
      _ <- Future.sequence(
        cmds.map(cmd =>
          connection
            .submitWithResultAndOffsetNoDedup(Seq(store.svcParty), Seq.empty, cmd, domainId)
            .flatMap {
              // make sure the store ingested our update so we don't
              // attempt to collect the same coupon twice
              case (offset, outcome) => acs.signalWhenIngested(offset).map(_ => Some(outcome))
            }
        )
      )
    } yield !cmds.isEmpty
  }

  def performWorkIfAvailable()(implicit
      traceContext: TraceContext
  ): scala.concurrent.Future[Boolean] = {
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
                expireRewardCouponsForRound(closedRound, coinRules)
            })
      })
  }
}
