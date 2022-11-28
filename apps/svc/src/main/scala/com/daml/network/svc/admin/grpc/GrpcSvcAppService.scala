package com.daml.network.svc.admin.grpc

import com.daml.ledger.javaapi.data.Template
import com.daml.ledger.javaapi.data.codegen.{
  Contract => CodegenContract,
  ContractCompanion,
  ContractId,
}
import com.daml.network.codegen.java.cc
import com.daml.network.codegen.java.cc.api.v1
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.svc.store.SvcStore
import com.daml.network.svc.v0.SvcServiceGrpc
import com.daml.network.svc.{SvcApp, v0}
import com.daml.network.util.Proto
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
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

  import GrpcSvcAppService.*

  override def getDebugInfo(request: Empty): Future[v0.GetDebugInfoResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { _ => _ =>
      for {
        coinRulesCids <- connection
          .activeContracts(store.svcParty, cc.coin.CoinRules.COMPANION)
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
          .exerciseCoinRules_MiningRound_Open(
            price,
            new v1.round.Round(request.round),
          )
        // TODO(M1-52): the command below is not safe w/o command dedup. This will though become safe once we change to the joint-open-round-advancement in M1-52.
        cid <- connection.submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
      } yield v0.OpenRoundResponse(Proto.encodeContractId(cid.exerciseResult))
    }

  override def startSummarizingRound(
      request: v0.StartSummarizingRoundRequest
  ): Future[v0.StartSummarizingRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        openRound <- getRound(cc.round.OpenMiningRound.COMPANION)(_.round)(request.round)
        coinRules <- store.getCoinRules()
        cmd = coinRules.value.contractId
          .exerciseCoinRules_MiningRound_StartSummarizing(openRound)
        cid <-
          connection.submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
      } yield v0.StartSummarizingRoundResponse(Proto.encodeContractId(cid.exerciseResult))
    }

  override def closeRound(request: v0.CloseRoundRequest): Future[v0.CloseRoundResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => _ =>
      for {
        issuingRound <- getRound(cc.round.IssuingMiningRound.COMPANION)(_.round)(request.round)
        totals = getTotalsPerRound(store)(request.round)
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
          connection.submitWithResultNoDedup(Seq(store.svcParty), Seq.empty, cmd)
      } yield v0.CloseRoundResponse(Proto.encodeContractId(cid.exerciseResult))
    }

  /** Query the ACS for the given template and filter by round. Returns a map from the validator party
    * to the corresponding contract id.
    * Fails if there is more than one round for a given validator.
    */
  private def getRound[TC <: CodegenContract[TCid, T], TCid <: ContractId[T], T <: Template](
      companion: ContractCompanion[TC, TCid, T]
  )(
      getRound: T => v1.round.Round
  )(round: Long): Future[TCid] =
    for {
      allRounds <- store.listContracts(companion)
    } yield {
      val filteredRounds = allRounds.value.filter { roundContract =>
        val roundId = getRound(roundContract.payload)
        roundId.number == round
      }
      require(
        filteredRounds.length == 1,
        s"Expected one round but got ${filteredRounds.length} rounds $filteredRounds",
      )
      filteredRounds(0).contractId
    }
}

object GrpcSvcAppService {

  case class RoundTotals(
      transferFees: BigDecimal = 0.0,
      adminFees: BigDecimal = 0.0,
      holdingFees: BigDecimal = 0.0,
      transferInputs: BigDecimal = 0.0,
      nonSelfTransferOutputs: BigDecimal = 0.0,
      selfTransferOutputs: BigDecimal = 0.0,
  )

  def getTotalsPerRound(store: SvcStore)(round: Long): RoundTotals = {
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
}
