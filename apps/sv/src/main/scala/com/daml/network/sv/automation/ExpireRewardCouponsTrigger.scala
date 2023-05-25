package com.daml.network.sv.automation

import com.daml.ledger.javaapi.data.codegen.{Exercised, Update}
import com.daml.network.automation.{PollingTrigger, TriggerContext}
import com.daml.network.codegen.java.cc.coin.{CoinRules, CoinRules_ClaimExpiredRewards}
import com.daml.network.codegen.java.cc.coin.UnclaimedReward.ContractId
import com.daml.network.codegen.java.cc.round.ClosedMiningRound
import com.daml.network.codegen.java.cn.svcrules.SvcRules
import com.daml.network.environment.CNLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import java.util.Optional
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class ExpireRewardCouponsTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CNLedgerConnection,
)(implicit
    override val ec: ExecutionContext,
    override val tracer: Tracer,
) extends PollingTrigger {

  private def getCmdsForRound(
      closedRound: Contract[ClosedMiningRound.ContractId, ClosedMiningRound],
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  )(implicit tc: TraceContext): Future[Seq[Update[Exercised[Optional[ContractId]]]]] = {
    for {
      appRewards <- store.listAppRewardCouponsGroupedByCounterparty(
        closedRound.payload.round.number,
        totalCouponsLimit = Some(100),
      )
      appRewardCmds = appRewards.map(group =>
        svcRules.contractId.exerciseSvcRules_ClaimExpiredRewards(
          coinRules.contractId,
          new CoinRules_ClaimExpiredRewards(
            closedRound.contractId,
            Seq.empty.asJava,
            group.asJava,
          ),
        )
      )
      validatorRewards <- store.listValidatorRewardCouponsGroupedByCounterparty(
        closedRound.payload.round.number,
        totalCouponsLimit = Some(100),
      )
      validatorRewardCmds = validatorRewards.map(group =>
        svcRules.contractId.exerciseSvcRules_ClaimExpiredRewards(
          coinRules.contractId,
          new CoinRules_ClaimExpiredRewards(
            closedRound.contractId,
            group.asJava,
            Seq.empty.asJava,
          ),
        )
      )
    } yield appRewardCmds ++ validatorRewardCmds
  }

  private def expireRewardCouponsForRound(
      closedRound: Contract[ClosedMiningRound.ContractId, ClosedMiningRound],
      svcRules: Contract[SvcRules.ContractId, SvcRules],
      coinRules: Contract[CoinRules.ContractId, CoinRules],
  )(implicit
      tc: TraceContext
  ): Future[Boolean] = {
    for {
      domainId <- store.domains.signalWhenConnected(store.defaultAcsDomain)
      cmds <- getCmdsForRound(closedRound, svcRules, coinRules)
      _ <- Future.sequence(
        cmds.map(cmd =>
          connection
            .submitWithResultNoDedup(
              Seq(store.key.svParty),
              Seq(store.key.svcParty),
              cmd,
              domainId,
            )
        )
      )
    } yield cmds.nonEmpty
  }

  private def performWorkAsLeader(svcRules: Contract[SvcRules.ContractId, SvcRules])(implicit
      traceContext: TraceContext
  ): Future[Boolean] = {
    store
      .lookupCoinRules()
      .flatMap({
        case None =>
          logger.warn(
            "Unexpected ledger state: SvcRules exist, but there is no CoinRules contract of the right version."
          )
          Future.successful(false)
        case Some(coinRules) =>
          store
            .lookupOldestClosedMiningRound()
            .flatMap({
              case None =>
                Future.successful(false)
              case Some(closedRound) =>
                expireRewardCouponsForRound(closedRound, svcRules, coinRules)
            })
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
