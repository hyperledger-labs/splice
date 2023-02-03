package com.daml.network.sv.automation

import akka.stream.Materializer
import com.daml.network.automation.{OnCreateTrigger, TaskOutcome, TaskSuccess, TriggerContext}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.sv.store.SvSvcStore
import com.daml.network.util.Contract
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.TraceContext
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class CoinRulesRequestTrigger(
    override protected val context: TriggerContext,
    store: SvSvcStore,
    connection: CoinLedgerConnection,
)(implicit
    ec: ExecutionContext,
    mat: Materializer,
    tracer: Tracer,
) extends OnCreateTrigger[
      cc.coin.CoinRulesRequest.Contract,
      cc.coin.CoinRulesRequest.ContractId,
      cc.coin.CoinRulesRequest,
    ](store.acs, cc.coin.CoinRulesRequest.COMPANION) {

  override def completeTask(
      req: Contract[
        cc.coin.CoinRulesRequest.ContractId,
        cc.coin.CoinRulesRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    // TODO(M3-46) consider extracting this pattern into a reusable abstract class (`SvTrigger`?)
    store
      .svIsLeader()
      .flatMap(if (_) {
        completeTaskAsLeader(req)
      } else {
        completeTaskAsFollower(req)
      })
  }

  def completeTaskAsLeader(
      req: Contract[
        cc.coin.CoinRulesRequest.ContractId,
        cc.coin.CoinRulesRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val validatorParty = PartyId.tryFromProtoPrimitive(req.payload.user)
    for {
      domainId <- store.domains.getUniqueDomainId()
      openMiningRounds <- store.acs.listContracts(cc.round.OpenMiningRound.COMPANION)
      issuingMiningRounds <- store.acs.listContracts(cc.round.IssuingMiningRound.COMPANION)
      coinRules <- store.getCoinRules()
      svcRules <- store.getSvcRules()
      cmds = svcRules.contractId
        .exerciseSvcRules_CoinRulesRequest_Accept(
          req.contractId,
          new cc.coin.CoinRulesRequest_Accept(
            coinRules.contractId,
            openMiningRounds.map(_.contractId).asJava,
            issuingMiningRounds.map(_.contractId).asJava,
          ),
        )
        .commands
        .asScala
        .toSeq
      // No command-dedup required, as the CoinRules contract is archived and recreated
      _ <- connection.submitCommandsNoDedup(
        Seq(store.key.svParty),
        Seq(store.key.svcParty),
        cmds,
        domainId,
      )
    } yield {
      TaskSuccess(s"accepted coin rules request from $validatorParty")
    }
  }

  @nowarn("cat=unused")
  def completeTaskAsFollower(
      req: Contract[
        cc.coin.CoinRulesRequest.ContractId,
        cc.coin.CoinRulesRequest,
      ]
  )(implicit tc: TraceContext): Future[TaskOutcome] = {
    val validatorParty = PartyId.tryFromProtoPrimitive(req.payload.user)
    Future.successful(
      TaskSuccess(s"ignored coin rules request from $validatorParty, as we're not the leader")
    )
  }
}
