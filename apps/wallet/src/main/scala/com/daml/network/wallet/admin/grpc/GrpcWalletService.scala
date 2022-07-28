package com.daml.network.wallet.admin.grpc

import java.util.concurrent.atomic.AtomicReference

import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.api.v1.commands.Command
import com.daml.ledger.api.v1.transaction_filter
import com.daml.ledger.api.v1.transaction_filter.{Filters, InclusiveFilters, TransactionFilter}
import com.daml.ledger.api.v1.value.Identifier
import com.daml.ledger.client.binding.{Primitive, Template}
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{InitializeRequest, InitializeResponse, WalletServiceGrpc}
import com.daml.network.util.{CoinUtil, Contract}
import com.digitalasset.canton.concurrent.Threading
import com.digitalasset.canton.ledger.api.client.{DecodeUtil, LedgerConnection}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.Coin.Coin
import com.digitalasset.network.CC.CoinRules.CoinRules
import com.digitalasset.network.CN.Wallet.{PaymentRequest => walletCodegen}
import com.digitalasset.network.`Package IDs`
import io.opentelemetry.api.trace.Tracer
import com.digitalasset.network.DA

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcWalletService(
    connection: CoinLedgerConnection,
    walletDamlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

  // TODO(Arne): remove once we have a CC Scan app.
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  val svcParty: AtomicReference[PartyId] = new AtomicReference[PartyId](null)
  @SuppressWarnings(Array("org.wartremover.warts.Null"))
  val validatorParty: AtomicReference[PartyId] = new AtomicReference[PartyId](null)

  private def getWalletParty() =
    for {
      partyO <- connection.getUser(walletDamlUser)
      party = partyO.getOrElse(
        sys.error(s"Unable to find party for user $walletDamlUser")
      )
    } yield party

  @nowarn("cat=unused")
  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- getWalletParty()
        activeContractsRes <- connection.activeContracts(
          CoinLedgerConnection.transactionFilter(walletParty, Coin.id)
        )
        coinsLAPI = activeContractsRes._1.flatMap(event => DecodeUtil.decodeCreated(Coin)(event))

      } yield {
        // TODO(i207): persist response to store
        val coinsProto = coinsLAPI.map(x => Contract.fromCodegenContract[Coin](x).toProtoV0)
        v0.ListResponse(coinsProto)
      }
    }

  @nowarn("cat=unused")
  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- getWalletParty()
        tapCmd = CoinRules
          .key(DA.Types.Tuple2(svcParty.get.toPrim, validatorParty.get.toPrim))
          .exerciseTap(
            walletParty.toPrim,
            walletParty.toPrim,
            BigDecimal(request.amount),
          )
          .command
        tx <- connection.submitCommand(Seq(walletParty), Seq(validatorParty.get()), Seq(tapCmd))
        coins = DecodeUtil.decodeAllCreated(Coin)(tx.getTransaction)
        _ = require(
          coins.length == 1,
          s"Expected tap to create only one coin but found ${coins.length} coins: $coins",
        )
      } yield v0.TapResponse(coins(0).contractId.toString)
    }

  @nowarn("cat=unused")
  override def listPaymentRequests(
      request: v0.ListPaymentRequestsRequest
  ): Future[v0.ListPaymentRequestsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        walletParty <- getWalletParty()
        activeContractsRes <- connection.activeContracts(
          CoinLedgerConnection.transactionFilter(walletParty, walletCodegen.PaymentRequest.id)
        )
        paymentRequestsLAPI = activeContractsRes._1.flatMap(event =>
          DecodeUtil.decodeCreated(walletCodegen.PaymentRequest)(event)
        )
      } yield {
        val filteredRequests = paymentRequestsLAPI.filter(contract =>
          PartyId.tryFromPrim(contract.value.payer) == walletParty
        )
        v0.ListPaymentRequestsResponse(
          filteredRequests.map(r =>
            Contract.fromCodegenContract[walletCodegen.PaymentRequest](r).toProtoV0
          )
        )
      }
    }

  override def initialize(request: InitializeRequest): Future[InitializeResponse] = {
    svcParty.set(
      PartyId.tryFromProtoPrimitive(request.svc.getOrElse(sys.error("svc party not set")))
    )
    validatorParty.set(
      PartyId.tryFromProtoPrimitive(request.validator.getOrElse(sys.error("svc party not set")))
    )
    Future.successful(v0.InitializeResponse())
  }
}
