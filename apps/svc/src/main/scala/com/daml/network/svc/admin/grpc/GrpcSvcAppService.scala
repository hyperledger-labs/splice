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
