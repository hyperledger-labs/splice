package com.daml.network.wallet.admin.grpc

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.Contract
import com.daml.network.wallet.util.WalletUtil
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.UploadablePackage
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{InitializeRequest, InitializeResponse, WalletServiceGrpc}
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.digitalasset.network.CN.Wallet.{PaymentRequest => walletCodegen}
import com.digitalasset.network.DA
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcWalletService(
    connection: CoinLedgerConnection,
    scanConnection: ScanConnection,
    walletDamlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    @nowarn("cat=unused")
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

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
          CoinLedgerConnection.transactionFilter(walletParty, coinCodegen.Coin.id)
        )
        coinsLAPI = activeContractsRes._1.flatMap(event =>
          DecodeUtil.decodeCreated(coinCodegen.Coin)(event)
        )

      } yield {
        // TODO(i207): persist response to store
        val coinsProto =
          coinsLAPI.map(x => Contract.fromCodegenContract[coinCodegen.Coin](x).toProtoV0)
        v0.ListResponse(coinsProto)
      }
    }

  @nowarn("cat=unused")
  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- getWalletParty()
        tapCmd = coinRulesCodegen.CoinRules
          .key(DA.Types.Tuple2(svcParty.toPrim, validatorParty.get.toPrim))
          .exerciseTap(
            walletParty.toPrim,
            walletParty.toPrim,
            BigDecimal(request.amount),
          )
          .command
        tx <- connection.submitCommand(Seq(walletParty), Seq(validatorParty.get()), Seq(tapCmd))
        coins = DecodeUtil.decodeAllCreated(coinCodegen.Coin)(tx.getTransaction)
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

  @nowarn("cat=unused")
  override def approvePaymentRequest(
      request: v0.ApprovePaymentRequestRequest
  ): Future[v0.ApprovePaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- getWalletParty()
        coinCid = Primitive.ContractId[coinCodegen.Coin](request.coinContractId)
        arg = walletCodegen.PaymentRequest_Approve(
          Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
        )
        approveCommand = Primitive
          .ContractId[walletCodegen.PaymentRequest](request.requestContractId)
          .exercisePaymentRequest_Approve(walletParty.toPrim, arg)
          .command
        tx <- connection.submitCommand(
          Seq(walletParty),
          Seq(validatorParty.get()),
          Seq(approveCommand),
        )
        payments = DecodeUtil.decodeAllCreated(walletCodegen.ApprovedPayment)(tx.getTransaction)
        _ = require(
          payments.length == 1,
          s"Expected approve payment to create only one approved payment but found ${payments.length} approved payments: $payments",
        )
      } yield v0.ApprovePaymentRequestResponse(payments(0).contractId.toString)
    }

  @nowarn("cat=unused")
  override def rejectPaymentRequest(
      request: v0.RejectPaymentRequestRequest
  ): Future[v0.RejectPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- getWalletParty()
        arg = walletCodegen.PaymentRequest_Reject()
        cmd = Primitive.ContractId
          .apply[walletCodegen.PaymentRequest](request.requestContractId)
          .exercisePaymentRequest_Reject(walletParty.toPrim, arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
      } yield v0.RejectPaymentRequestResponse()
    }

  override def initialize(request: InitializeRequest): Future[InitializeResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => _ =>
      validatorParty.set(
        PartyId.tryFromProtoPrimitive(
          request.validator.getOrElse(sys.error("validator party not set"))
        )
      )

      for {
        _ <- connection.uploadDarFile(
          WalletUtil
        ) // TODO(i353) move away from dar upload during init
      } yield v0.InitializeResponse()
    }
}
