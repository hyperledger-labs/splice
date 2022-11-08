package com.daml.network.svc.admin.grpc

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  Contract as CodegenContract,
  ContractCompanion,
  ContractId,
}
import com.daml.network.codegen.java.cc
import com.daml.network.environment.{CoinLedgerClient, CoinLedgerConnection}
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.svc.{SvcApp, v0}
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.ledger.api.client.{JavaDecodeUtil as DecodeUtil}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.google.protobuf.empty.Empty
import io.opentelemetry.api.trace.Tracer

import scala.concurrent.{ExecutionContext, Future}

class GrpcSvcAppService(
    ledgerClient: CoinLedgerClient,
    svcUserName: String,
    store: SvcStore,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends SvcServiceGrpc.SvcService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcSvcAppService")

  import GrpcSvcAppService._

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      for {
        coinRulesCids <- connection
          .activeContracts(store.svcParty, cc.coinrules.CoinRules.COMPANION)
          .map(_.map(_.id))
      } yield v0.GetDebugInfoResponse(
        svcUser = svcUserName,
        svcPartyId = Proto.encode(store.svcParty),
        coinPackageId = SvcApp.coinPackage.packageId,
        coinRulesContractIds = coinRulesCids.map(Proto.encodeContractId(_)),
      )
    }

  // NOTE: the commands below have not been converted to use the store, as they'll go away when we automation issuance
  override def openRound(request: v0.OpenRoundRequest): Future[v0.OpenRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      val price = Proto.tryDecode(Proto.JavaBigDecimal)(request.coinPrice)
      for {
        coinRules <- store.getCoinRules()
        cmd = coinRules.value.contractId
          .exerciseCoinRules_MiningRound_Open(price, new cc.round.Round(request.round))
        cid <- connection.submitWithResult(Seq(store.svcParty), Seq.empty, cmd)
      } yield v0.OpenRoundResponse(Proto.encodeContractId(cid.exerciseResult))
    }

  override def startClosingRound(
      request: v0.StartClosingRoundRequest
  ): Future[v0.StartClosingRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        openRound <- getRound(cc.round.OpenMiningRound.COMPANION)(_.round)(request.round)
        coinRules <- store.getCoinRules()
        cmd = coinRules.value.contractId
          .exerciseCoinRules_MiningRound_StartClosing(openRound)
        cid <-
          connection.submitWithResult(Seq(store.svcParty), Seq.empty, cmd)
      } yield v0.StartClosingRoundResponse(Proto.encodeContractId(cid.exerciseResult))
    }

  override def startIssuingRound(
      request: v0.StartIssuingRoundRequest
  ): Future[v0.StartIssuingRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        closingRound <- getRound(cc.round.ClosingMiningRound.COMPANION)(_.round)(request.round)
        rewards <- queryRewards(store.svcParty, request.round)
        totalBurn = rewards.totalBurn
        coinRules <- store.getCoinRules()
        cmd = coinRules.value.contractId
          .exerciseCoinRules_MiningRound_StartIssuing(closingRound, totalBurn.bigDecimal)
        cid <-
          connection.submitWithResult(Seq(store.svcParty), Seq.empty, cmd)
      } yield v0.StartIssuingRoundResponse(
        Proto.encode(totalBurn),
        Proto.encodeContractId(cid.exerciseResult),
      )
    }

  override def closeRound(request: v0.CloseRoundRequest): Future[v0.CloseRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        issuingRound <- getRound(cc.round.IssuingMiningRound.COMPANION)(_.round)(request.round)
        totals = getTotalsPerRound(request.round)
        coinRules <- store.getCoinRules()
        cmd =
          coinRules.value.contractId
            .exerciseCoinRules_MiningRound_Close(
              issuingRound,
              totals.transferFees.bigDecimal,
              totals.adminFees.bigDecimal,
              totals.holdingFees.bigDecimal,
              totals.transferInputs.bigDecimal,
              totals.nonSelfTransferOutputs.bigDecimal,
              totals.selfTransferOutputs.bigDecimal,
            )
        cid <-
          connection.submitWithResult(Seq(store.svcParty), Seq.empty, cmd)
      } yield v0.CloseRoundResponse(Proto.encodeContractId(cid.exerciseResult))
    }

  private case class RoundTotals(
      transferFees: BigDecimal = 0.0,
      adminFees: BigDecimal = 0.0,
      holdingFees: BigDecimal = 0.0,
      transferInputs: BigDecimal = 0.0,
      nonSelfTransferOutputs: BigDecimal = 0.0,
      selfTransferOutputs: BigDecimal = 0.0,
  )

  private def getTotalsPerRound(round: Long): RoundTotals = {
    val transfers = store.events.getTransferSummariesPerRound(round)
    transfers.foldLeft(RoundTotals())((t, transfer) => {
      RoundTotals(
        t.transferFees + transfer.totalTransferFees,
        t.adminFees + transfer.senderAdminFees,
        t.holdingFees + transfer.senderHoldingFees,
        t.transferInputs + transfer.inQuantity,
        t.nonSelfTransferOutputs + transfer.nonSelfOutQuantity,
        t.selfTransferOutputs + transfer.selfOutQuantity,
      )
    })
  }

  override def archiveRound(request: v0.ArchiveRoundRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        closedRound <- getRound(cc.round.ClosedMiningRound.COMPANION)(_.round)(request.round)
        coinRules <- store.getCoinRules()
        cmd =
          coinRules.value.contractId
            .exerciseCoinRules_MiningRound_Archive(closedRound)
        _ <- connection.submitWithResult(Seq(store.svcParty), Seq.empty, cmd)
      } yield Empty()
    }

  /** Query the ACS for the given template and filter by round. Returns a map from the validator party
    * to the corresponding contract id.
    * Fails if there is more than one round for a given validator.
    */
  private def getRound[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(
      getRound: T => cc.round.Round
  )(round: Long): Future[TCid] =
    for {
      allRounds <- connection.activeContracts(store.svcParty, companion)
    } yield {
      val filteredRounds = allRounds.filter { case roundContract =>
        val roundId = getRound(roundContract.data)
        roundId.number == round
      }
      require(
        filteredRounds.length == 1,
        s"Expected one round but got ${filteredRounds.length} rounds $filteredRounds",
      )
      filteredRounds(0).id
    }

  /** Query the open reward contracts for a given round. This should only be used
    * on a ClosingMiningRound.
    */
  private def queryRewards(p: PartyId, round: Long): Future[RoundRewards] = for {
    activeContracts <- connection.activeContracts(
      CoinLedgerConnection.transactionFilterByParty(
        Map(p -> Seq(cc.coin.AppReward.TEMPLATE_ID, cc.coin.ValidatorReward.TEMPLATE_ID))
      )
    )
  } yield {
    val appRewards =
      activeContracts.flatMap(ev => DecodeUtil.decodeCreated(cc.coin.AppReward.COMPANION)(ev))
    val validatorRewards =
      activeContracts.flatMap(ev => DecodeUtil.decodeCreated(cc.coin.ValidatorReward.COMPANION)(ev))
    RoundRewards(
      round = round,
      appRewards = appRewards.filter(c => c.data.round.number == round),
      validatorRewards = validatorRewards.filter(c => c.data.round.number == round),
    )
  }
}

object GrpcSvcAppService {

  /** The rewards issued for a given round.
    */
  private case class RoundRewards(
      round: Long,
      appRewards: Seq[CodegenContract[cc.coin.AppReward.ContractId, cc.coin.AppReward]],
      validatorRewards: Seq[
        CodegenContract[cc.coin.ValidatorReward.ContractId, cc.coin.ValidatorReward]
      ],
  ) {

    /** Calculate the total burn for the given round based on the rewards issued in that round.
      */
    def totalBurn: BigDecimal =
      appRewards.map[BigDecimal](r => BigDecimal(r.data.quantity)).sum + validatorRewards
        .map[BigDecimal](r => BigDecimal(r.data.quantity))
        .sum
  }
}
