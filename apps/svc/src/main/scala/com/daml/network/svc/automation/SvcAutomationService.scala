package com.daml.network.svc.automation

import akka.stream.Materializer
import com.daml.network.automation.{
  AcsIngestionService,
  AuditLogIngestionService,
  AutomationService,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.{CoinLedgerClient, CoinRetries}
import com.daml.network.svc.admin.grpc.GrpcSvcAppService.getTotalsPerRound
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.svc.store.SvcStore
import com.daml.network.util.JavaContract
import com.digitalasset.canton.config.{ClockConfig, ProcessingTimeout}
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.topology.PartyId
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.jdk.CollectionConverters.*

@nowarn("msg=match may not be exhaustive")
class SvcAutomationService(
    clockConfig: ClockConfig,
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
) extends AutomationService(config.automation, clockConfig, retryProvider) {
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
        _ <- getCoinRules()
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

  registerPollingTrigger("cycle OpenMiningRounds", config.automation.pollingInterval, connection) {
    (now, logger) =>
      { implicit tc =>
        for {
          rules <- getCoinRules()
          QueryResult(_, openMiningRounds) <- store.acs.listContracts(
            cc.round.OpenMiningRound.COMPANION
          )
          res <- {
            val Seq(toArchive, middle, latest) = openMiningRounds
              .sortBy(contract => contract.payload.round.number)

            // checking some of the same conditions that are also checked on the ledger because ledger operations are expensive
            // so we check the conditions that are most likely to fail.
            val isPastTargetClosesAt = now.toInstant
              .isAfter(
                toArchive.payload.targetClosesAt.plus(
                  config.automation.clockSkewAutomationDelay.duration
                )
              )
            val midPointForMiddleRound = middle.payload.opensAt
              .plus(config.initialTickDuration.duration)
              .plus(config.automation.clockSkewAutomationDelay.duration)
            val isPastLatestOpensAt = now.toInstant
              .isAfter(
                latest.payload.opensAt.plus(config.automation.clockSkewAutomationDelay.duration)
              )
            val middleRoundOpenLongEnough = now.toInstant.isAfter(midPointForMiddleRound)
            if (isPastTargetClosesAt && middleRoundOpenLongEnough && isPastLatestOpensAt) {
              val cmds = rules.contractId
                .exerciseCoinRules_AdvanceOpenMiningRounds(
                  config.coinPrice.bigDecimal,
                  toArchive.contractId,
                  middle.contractId,
                  latest.contractId,
                )
                .commands
                .asScala
                .toSeq
              connection
                .submitCommandsNoDedup(Seq(store.svcParty), Seq(), cmds)
                .map(_ =>
                  Some(
                    s"successfully advanced the rounds and archived round ${toArchive.payload.round.number}"
                  )
                )
            } else Future.successful(None)
          }

        } yield res
      }
  }

  registerPollingTrigger(
    "archive IssuingMiningRounds past their targetClosesAt",
    config.automation.pollingInterval,
    connection,
  ) {
    (now, logger) =>
      { implicit tc =>
        for {
          coinRules <- store.getCoinRules()
          QueryResult(_, issuingMiningRounds) <- store.acs.listContracts(
            cc.round.IssuingMiningRound.COMPANION
          )
          roundsSorted = issuingMiningRounds
            .sortBy(contract => contract.payload.targetClosesAt)
            .headOption
          res <- roundsSorted match {
            case None =>
              Future.successful(None)
            case Some(earliestClosingIssuingRound) =>
              val totals =
                getTotalsPerRound(store)(earliestClosingIssuingRound.payload.round.number)
              val oldestRoundCanBeClosed =
                now.toInstant.isAfter(earliestClosingIssuingRound.payload.targetClosesAt)
              if (oldestRoundCanBeClosed) {
                val cmd = coinRules.value.contractId
                  .exerciseCoinRules_MiningRound_Close(
                    earliestClosingIssuingRound.contractId,
                    totals.transferFees.bigDecimal,
                    totals.adminFees.bigDecimal,
                    totals.holdingFees.bigDecimal,
                    totals.transferInputs.bigDecimal,
                    totals.nonSelfTransferOutputs.bigDecimal,
                    totals.selfTransferOutputs.bigDecimal,
                  )
                connection
                  .submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
                  .map(cid => Some(s"successfully created the closed mining round with cid $cid"))
              } else {
                Future.successful(None)
              }

          }
        } yield res
      }
  }

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

  private def getCoinRules()
      : Future[JavaContract[cc.coin.CoinRules.ContractId, cc.coin.CoinRules]] = {
    store.lookupCoinRules().map(_.value).flatMap {
      case None =>
        // SCV setup is not yet complete: throw a StatusRuntimeException as that properly triggers the retry loop
        Future.failed(
          new StatusRuntimeException(
            Status.NOT_FOUND
              .withDescription(s"Could not find CoinRules for SVC party ${store.svcParty}")
          )
        )

      case Some(rules) => Future.successful(rules)
    }
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
