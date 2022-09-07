package com.daml.network.wallet.admin.grpc

import cats.implicits._
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.util.{Contract, Proto, Value}
import com.daml.network.wallet.util.WalletUtil
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{InitializeRequest, WalletServiceGrpc}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.daml.network.codegen.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.codegen.DA
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcWalletService(
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    walletDamlUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcWalletService")

  val validatorParty: AtomicReference[Option[PartyId]] = new AtomicReference[Option[PartyId]](None)

  def getValidatorParty: PartyId =
    validatorParty.get.getOrElse(
      throw new StatusRuntimeException(
        Status.FAILED_PRECONDITION.withDescription(
          "Wallet is not initialized, run wallet.initialize(validatorParty) first"
        )
      )
    )

  @nowarn("cat=unused")
  override def list(request: Empty): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        coinsLAPI <- connection.activeContracts(walletParty, coinCodegen.Coin)
      } yield {
        // TODO(i207): persist response to store
        val coinsProto =
          coinsLAPI.map(x => Contract.fromCodegenContract[coinCodegen.Coin](x).toProtoV0)
        v0.ListResponse(coinsProto)
      }
    }

  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        tapCmd = coinRulesCodegen.CoinRules
          .key(DA.Types.Tuple2(svcParty.toPrim, getValidatorParty.toPrim))
          .exerciseCoinRules_Tap(
            walletParty.toPrim,
            quantity,
          )
        coinCid <- connection.submitWithResult(Seq(walletParty), Seq(getValidatorParty), tapCmd)
      } yield v0.TapResponse(Proto.encode(coinCid))
    }

  @nowarn("cat=unused")
  override def listAppPaymentRequests(
      request: Empty
  ): Future[v0.ListAppPaymentRequestsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        paymentRequestsLAPI <- connection
          .activeContracts(walletParty, walletCodegen.AppPaymentRequest)
      } yield {
        val filteredRequests = paymentRequestsLAPI.filter(contract =>
          PartyId.tryFromPrim(contract.value.sender) == walletParty
        )
        v0.ListAppPaymentRequestsResponse(
          filteredRequests.map(r =>
            Contract.fromCodegenContract[walletCodegen.AppPaymentRequest](r).toProtoV0
          )
        )
      }
    }

  override def acceptAppPaymentRequest(
      request: v0.AcceptAppPaymentRequestRequest
  ): Future[v0.AcceptAppPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        coinCid = Proto.tryDecodeContractId[coinCodegen.Coin](request.coinContractId)
        arg = walletCodegen.AppPaymentRequest_Accept(
          Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
        )
        requestCid = Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](
          request.requestContractId
        )
        acceptCommand = requestCid
          .exerciseAppPaymentRequest_Accept(arg)
        paymentCid <- connection.submitWithResult(
          Seq(walletParty),
          Seq(getValidatorParty),
          acceptCommand,
        )
      } yield v0.AcceptAppPaymentRequestResponse(
        Proto.encode(paymentCid)
      )
    }

  override def rejectAppPaymentRequest(
      request: v0.RejectAppPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        arg = walletCodegen.AppPaymentRequest_Reject()
        requestCid = Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseAppPaymentRequest_Reject(arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
    }

  @nowarn("cat=unused")
  override def listAcceptedAppPayments(request: Empty): Future[v0.ListAcceptedAppPaymentsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(walletDamlUser)
        acceptedAppPayments <- connection.activeContracts(party, walletCodegen.AcceptedAppPayment)
      } yield {
        val filtered = acceptedAppPayments.filter(c => c.value.sender == party.toPrim)
        v0.ListAcceptedAppPaymentsResponse(
          filtered.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  @nowarn("cat=unused")
  override def listPaymentChannelProposals(
      request: Empty
  ): Future[v0.ListPaymentChannelProposalsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        proposalsLAPI <- connection
          .activeContracts(walletParty, walletCodegen.PaymentChannelProposal)
      } yield {
        v0.ListPaymentChannelProposalsResponse(
          proposalsLAPI.map(r =>
            Contract.fromCodegenContract[walletCodegen.PaymentChannelProposal](r).toProtoV0
          )
        )
      }
    }

  @nowarn("cat=unused")
  override def listPaymentChannels(
      request: Empty
  ): Future[v0.ListPaymentChannelsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        channelsLAPI <- connection
          .activeContracts(walletParty, walletCodegen.PaymentChannel)
      } yield {
        v0.ListPaymentChannelsResponse(
          channelsLAPI.map(r =>
            Contract.fromCodegenContract[walletCodegen.PaymentChannel](r).toProtoV0
          )
        )
      }
    }

  override def proposePaymentChannel(
      request: v0.ProposePaymentChannelRequest
  ): Future[v0.ProposePaymentChannelResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        svcParty <- scanConnection.getSvcPartyId()
        // TODO(M1-07): guard making the proposal by a check that a like channel does not yet exist
        receiver = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
        cmd = walletCodegen
          .PaymentChannelProposal(
            proposer = walletParty.toPrim,
            channel = walletCodegen.PaymentChannel(
              sender = walletParty.toPrim,
              receiver = receiver.toPrim,
              svc = svcParty.toPrim,
              allowRequests = request.allowRequests,
              allowOffers = request.allowOffers,
              allowDirectTransfers = request.allowDirectTransfers,
              senderTransferFeeRatio =
                Proto.tryDecode(Proto.BigDecimal)(request.senderTransferFeeRatio),
            ),
            replacesChannel = request.replacesChannelId.map(
              Proto.tryDecodeContractId[walletCodegen.PaymentChannel](_)
            ),
          )
          .create
        proposalCid <- connection.submitWithResult(
          Seq(walletParty),
          Seq(),
          cmd,
        )
      } yield v0.ProposePaymentChannelResponse(
        proposalContractId = Proto.encode(proposalCid)
      )
    }

  override def acceptPaymentChannelProposal(
      request: v0.AcceptPaymentChannelProposalRequest
  ): Future[v0.AcceptPaymentChannelProposalResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        arg = walletCodegen.PaymentChannelProposal_Accept()
        // TODO(M3-01): guard accepting the proposal by a check that a channel with the same key does not yet exist
        proposalCid = Proto.tryDecodeContractId[walletCodegen.PaymentChannelProposal](
          request.proposalContractId
        )
        cmd = proposalCid
          .exercisePaymentChannelProposal_Accept(arg)
        channelCid <- connection.submitWithResult(
          Seq(walletParty),
          Seq(),
          cmd,
        )
      } yield v0.AcceptPaymentChannelProposalResponse(
        channelContractId = Proto.encode(channelCid)
      )
    }

  override def cancelPaymentChannel(request: v0.CancelPaymentChannelRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
        cmd = walletCodegen.PaymentChannel
          .key(DA.Types.Tuple3(senderParty.toPrim, walletParty.toPrim, svcParty.toPrim))
          .exercisePaymentChannel_Cancel()
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(getValidatorParty),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def executeDirectTransfer(
      request: v0.ExecuteDirectTransferRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        coinCid = Proto.tryDecodeContractId[coinCodegen.Coin](request.coinContractId)
        receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
        quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        arg = walletCodegen.PaymentChannel_ExecuteDirectTransfer(
          inputs = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid)),
          quantity = quantity,
          payload = "wallet: execute direct transfer",
        )
        cmd = walletCodegen.PaymentChannel
          .key(DA.Types.Tuple3(walletParty.toPrim, receiverParty.toPrim, svcParty.toPrim))
          .exercisePaymentChannel_ExecuteDirectTransfer(arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(getValidatorParty),
          Seq(cmd),
        )
      } yield Empty()
    }

  @nowarn("cat=unused")
  override def listAppRewards(request: Empty): Future[v0.ListAppRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(walletDamlUser)
        appRewards <- connection.activeContracts(party, coinCodegen.AppReward)
      } yield {
        val filtered = appRewards.filter(c => c.value.owner == party.toPrim)
        v0.ListAppRewardsResponse(
          filtered.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  @nowarn("cat=unused")
  override def listValidatorRewards(request: Empty): Future[v0.ListValidatorRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(walletDamlUser)
        validatorRights <- connection.activeContracts(party, coinCodegen.ValidatorRight)
        users = validatorRights
          .filter(c => c.value.validator == party.toPrim)
          .map(c => PartyId.tryFromPrim(c.value.user))
          .toSet
        validatorRewards <-
          if (users.isEmpty) {
            Future.successful(Seq.empty)
          } else {
            connection.activeContracts(users, coinCodegen.ValidatorReward)
          }
      } yield {
        v0.ListValidatorRewardsResponse(
          validatorRewards.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  override def createOnChannelPaymentRequest(
      request: v0.CreateOnChannelPaymentRequestRequest
  ): Future[v0.CreateOnChannelPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
        quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        arg = walletCodegen.PaymentChannel_CreatePaymentRequest(
          quantity = quantity,
          description = request.description,
        )
        cmd = walletCodegen.PaymentChannel
          .key(DA.Types.Tuple3(senderParty.toPrim, walletParty.toPrim, svcParty.toPrim))
          .exercisePaymentChannel_CreatePaymentRequest(arg)
        requestCid <- connection.submitWithResult(
          Seq(walletParty),
          Seq(),
          cmd,
        )
      } yield v0.CreateOnChannelPaymentRequestResponse(
        requestContractId = Proto.encode(requestCid)
      )
    }

  @nowarn("cat=unused")
  override def listOnChannelPaymentRequests(
      request: Empty
  ): Future[v0.ListOnChannelPaymentRequestsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        paymentRequestsLAPI <- connection
          .activeContracts(walletParty, walletCodegen.OnChannelPaymentRequest)
      } yield {
        v0.ListOnChannelPaymentRequestsResponse(
          paymentRequestsLAPI.map(r =>
            Contract.fromCodegenContract[walletCodegen.OnChannelPaymentRequest](r).toProtoV0
          )
        )
      }
    }

  override def acceptOnChannelPaymentRequest(
      request: v0.AcceptOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        coinCid = Proto.tryDecodeContractId[coinCodegen.Coin](request.coinContractId)
        arg = walletCodegen.OnChannelPaymentRequest_Accept(
          inputs = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
        )
        requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseOnChannelPaymentRequest_Accept(arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(getValidatorParty),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def rejectOnChannelPaymentRequest(
      request: v0.RejectOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        arg = walletCodegen.OnChannelPaymentRequest_Reject()
        requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseOnChannelPaymentRequest_Reject(arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def withdrawOnChannelPaymentRequest(
      request: v0.WithdrawOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        arg = walletCodegen.OnChannelPaymentRequest_Withdraw()
        requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseOnChannelPaymentRequest_Withdraw(arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def redistribute(request: v0.RedistributeRequest): Future[v0.RedistributeResponse] = {
    def toOutput(output: v0.RedistributeOutput): coinRulesCodegen.TransferOutput =
      coinRulesCodegen.TransferOutput.OutputSenderCoin(
        lock = None,
        exactQuantity =
          if (output.quantity.isEmpty) None
          else Some(Proto.tryDecode(Proto.BigDecimal)(output.quantity)),
      )
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(walletDamlUser)
        svcParty <- scanConnection.getSvcPartyId()
        inputs = request.inputs
          .traverse(Value.fromProto[coinRulesCodegen.TransferInput](_).map(_.value))
          .valueOr(err => throw err.toAdminError.asGrpcError)
        outputs = request.outputs.map(toOutput)
        cmd = coinRulesCodegen.CoinRules
          .key(DA.Types.Tuple2(svcParty.toPrim, getValidatorParty.toPrim))
          .exerciseCoinRules_Transfer(
            coinRulesCodegen.Transfer(
              sender = party.toPrim,
              inputs = inputs,
              outputs = outputs,
              payload = "redistribute",
            )
          )
        transferResults <- connection.submitWithResult(
          Seq(party),
          Seq(getValidatorParty),
          cmd,
        )
      } yield {
        v0.RedistributeResponse(
          transferResults.createdCoins.collect {
            case coinRulesCodegen.CreatedCoin.TransferResultCoin(cid) =>
              Proto.encode(cid)
          }
        )
      }
    }
  }

  override def initialize(request: InitializeRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => _ =>
      validatorParty.set(Some(PartyId.tryFromProtoPrimitive(request.validatorPartyId)))

      for {
        _ <- connection.uploadDarFile(
          WalletUtil
        ) // TODO(i353) move away from dar upload during init
      } yield Empty()
    }
}
