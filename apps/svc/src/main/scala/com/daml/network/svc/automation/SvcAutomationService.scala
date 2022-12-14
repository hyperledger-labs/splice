package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  AcsIngestionService,
  AuditLogIngestionService,
  AutomationService,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.JavaContract
import com.digitalasset.canton.config.ProcessingTimeout
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.PartyId
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

class SvcAutomationService(
    clock: Clock,
    config: LocalSvcAppConfig,
    store: SvcStore,
    ledgerClient: CoinLedgerClient,
    retryProvider: CoinRetries,
    override protected val loggerFactory: NamedLoggerFactory,
    override protected val timeouts: ProcessingTimeout,
)(implicit
    ec: ExecutionContextExecutor,
    mat: Materializer,
    tracer: Tracer,
) extends AutomationService(config.automation, clock, retryProvider) {
  import com.daml.network.store.AcsStore.QueryResult

  private val connection = registerResource(ledgerClient.connection(this.getClass.getSimpleName))

  registerService(
    new AcsIngestionService(
      store.getClass.getSimpleName,
      store.acsIngestionSink,
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  registerService(
    new AuditLogIngestionService(
      "svcRoundSummaryCollectionService",
      new RoundSummaryIngestionService(
        store.svcParty,
        connection,
        store.events,
        loggerFactory,
      ),
      connection,
      retryProvider,
      loggerFactory,
      timeouts,
    )
  )

  registerTrigger(
    "handleCoinRulesRequest",
    store.acs.streamContracts(cc.coin.CoinRulesRequest.COMPANION),
  )((req, logger) => { implicit traceContext =>
    {
      for {
        // Guard the action by a lookup for the SVC's own CoinRules to ensure that all of the dependent state
        // has already been created.
        _ <- store.getCoinRules()
        validatorParty = PartyId.tryFromProtoPrimitive(req.payload.user)
        // NOTE: this is NOT SAFE under concurrent changes to the XXXMiningRounds contracts
        // That is OK here, as we assume that on-boarding of validators happens before.
        // TODO(M3-90): make this safe under concurrent round management and onboarding
        QueryResult(_, openMiningRounds) <- store.acs.listContracts(
          cc.round.OpenMiningRound.COMPANION
        )
        QueryResult(_, issuingMiningRounds) <- store.acs.listContracts(
          cc.round.IssuingMiningRound.COMPANION
        )
        QueryResult(_, coinRules) <- store.getCoinRules()
        cmds = req.contractId
          .exerciseCoinRulesRequest_Accept(
            coinRules.contractId,
            openMiningRounds.map(_.contractId).asJava,
            issuingMiningRounds.map(_.contractId).asJava,
          )
          .commands
          .asScala
          .toSeq
        // No command-dedup required, as the CoinRules contract is archived and recreated
        _ <- connection.submitCommandsNoDedup(Seq(store.svcParty), Seq(), cmds)
      } yield Some(s"accepted coin rules request from $validatorParty")
    }
  })

  registerNewStyleTrigger(
    new AdvanceOpenMiningRoundTrigger(triggerContext, config, store, connection)
  )
  registerNewStyleTrigger(
    new ExpireIssuingMiningRoundTrigger(triggerContext, store, connection)
  )

  registerTrigger(
    "archive summarizing rounds and create issuing rounds",
    store.acs.streamContracts(cc.round.SummarizingMiningRound.COMPANION),
  ) { (summarizingRound, logger) => implicit tc =>
    for {
      rewards <- queryRewards(summarizingRound.payload.round.number)
      totalBurn = rewards.totalBurn
      coinRules <- store.getCoinRules()
      // TODO(M3-06): consider querying the round audit store (once we have it) and
      // passing along the opensAt time of the previous IssuingMiningRound
      // see discussion: https://docs.google.com/document/d/1RAcc4uJKjRtPKDmVglVhqg-y58fCJ7xyljPbwimE-IA/edit?disco=AAAAjyuFFEw
      cmd = coinRules.value.contractId
        .exerciseCoinRules_MiningRound_StartIssuing(
          summarizingRound.contractId,
          totalBurn.bigDecimal,
        )
      cid <-
        connection.submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
    } yield Some(
      s"successfully archived summarizing mining round with burn ${totalBurn}, and created issuing mining round with cid $cid"
    )
  }

  registerTrigger(
    "archive closed rounds and unclaimed rewards",
    store.acs.streamContracts(cc.round.ClosedMiningRound.COMPANION),
  ) { (closedRound, logger) => implicit tc =>
    for {
      coinRules <- store.getCoinRules()
      // TODO(M3-06): claim unclaimed rewards
      cmd = coinRules.value.contractId
        .exerciseCoinRules_MiningRound_Archive(
          closedRound.contractId
        )
        .commands
        .asScala
        .toSeq
      _ <-
        connection.submitCommandsNoDedup(Seq(store.svcParty), Seq.empty, cmd)
    } yield Some(s"successfully archived closed mining round $closedRound")
  }

  /** The rewards issued for a given round.
    */
  private case class RoundRewards(
      round: Long,
      appRewards: Seq[JavaContract[cc.coin.AppReward.ContractId, cc.coin.AppReward]],
      validatorRewards: Seq[
        JavaContract[cc.coin.ValidatorReward.ContractId, cc.coin.ValidatorReward]
      ],
  ) {

    /** Calculate the total burn for the given round based on the rewards issued in that round.
      */
    def totalBurn: BigDecimal =
      appRewards.map[BigDecimal](r => BigDecimal(r.payload.quantity)).sum + validatorRewards
        .map[BigDecimal](r => BigDecimal(r.payload.quantity))
        .sum
  }

  /** Query the open reward contracts for a given round. This should only be used
    * for a SummarizingMiningRound.
    */
  private def queryRewards(round: Long)(implicit ec: ExecutionContext): Future[RoundRewards] =
    for {
      appRewards <- store.acs.listContracts(
        cc.coin.AppReward.COMPANION,
        (c: JavaContract[cc.coin.AppReward.ContractId, cc.coin.AppReward]) =>
          c.payload.round.number == round,
      )
      validatorRewards <- store.acs.listContracts(
        cc.coin.ValidatorReward.COMPANION,
        (c: JavaContract[cc.coin.ValidatorReward.ContractId, cc.coin.ValidatorReward]) =>
          c.payload.round.number == round,
      )
    } yield {
      RoundRewards(
        round = round,
        appRewards = appRewards.value,
        validatorRewards = validatorRewards.value,
      )
    }
}
