package com.daml.network.wallet.admin.grpc

import cats.implicits._
import com.daml.ledger.api.refinements.ApiTypes
import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinLedgerConnection
import com.daml.network.util.{Contract, Proto, Value}
import com.daml.network.wallet.util.WalletUtil
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{InitializeRequest, WalletServiceGrpc}
import com.digitalasset.canton.ledger.api.client.DecodeUtil
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.Spanning
import com.digitalasset.network.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.digitalasset.network.CN.{Wallet => walletCodegen}
import com.digitalasset.network.DA
import com.google.protobuf.empty.Empty
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

  @nowarn("cat=unused")
  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(walletDamlUser)
        quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        tapCmd = coinRulesCodegen.CoinRules
          .key(DA.Types.Tuple2(svcParty.toPrim, validatorParty.get.toPrim))
          .exerciseTap(
            walletParty.toPrim,
            walletParty.toPrim,
            quantity,
          )
          .command
        tx <- connection.submitCommand(Seq(walletParty), Seq(validatorParty.get()), Seq(tapCmd))
        coins = DecodeUtil.decodeAllCreated(coinCodegen.Coin)(tx.getTransaction)
        _ = require(
          coins.length == 1,
          s"Expected tap to create only one coin but found ${coins.length} coins: $coins",
        )
      } yield v0.TapResponse(Proto.encode(coins(0).contractId))
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

  @nowarn("cat=unused")
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
          .exerciseAppPaymentRequest_Accept(walletParty.toPrim, arg)
          .command
        tx <- connection.submitCommand(
          Seq(walletParty),
          Seq(validatorParty.get()),
          Seq(acceptCommand),
        )
        payments = DecodeUtil.decodeAllCreated(walletCodegen.AcceptedAppPayment)(tx.getTransaction)
        _ = require(
          payments.length == 1,
          s"Expected accept payment to create only one accepted payment but found ${payments.length} accepted payments: $payments",
        )
      } yield v0.AcceptAppPaymentRequestResponse(
        Proto.encode(payments(0).contractId)
      )
    }

  @nowarn("cat=unused")
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
          .exerciseAppPaymentRequest_Reject(walletParty.toPrim, arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
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
              // TODO(M1-07): make channel parameters configurable
              allowRequests = true,
              allowOffers = true,
              allowDirectTransfers = true,
              senderTransferFeeRatio = 1.0,
            ),
          )
          .create
          .command
        tx <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
        proposals = DecodeUtil.decodeAllCreated(walletCodegen.PaymentChannelProposal)(
          tx.getTransaction
        )
        _ = require(
          proposals.length == 1,
          s"Expected bare create to create only one proposal, but found ${proposals.length} proposals: $proposals",
        )
      } yield v0.ProposePaymentChannelResponse(
        proposalContractId = Proto.encode(proposals(0).contractId)
      )
    }

  @nowarn("cat=unused")
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
          .exercisePaymentChannelProposal_Accept(walletParty.toPrim, arg)
          .command
        tx <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
        channels = DecodeUtil.decodeAllCreated(walletCodegen.PaymentChannel)(
          tx.getTransaction
        )
        _ = require(
          channels.length == 1,
          s"Expected accept payment channel proposal to create only one channel, but found ${channels.length} channels: $channels",
        )
      } yield v0.AcceptPaymentChannelProposalResponse(
        channelContractId = Proto.encode(channels(0).contractId)
      )
    }

  @nowarn("cat=unused")
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
          .exercisePaymentChannel_ExecuteDirectTransfer(walletParty.toPrim, arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(validatorParty.get()),
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

  @nowarn("cat=unused")
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
          .exercisePaymentChannel_CreatePaymentRequest(walletParty.toPrim, arg)
          .command
        tx <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
        requests = DecodeUtil.decodeAllCreated(walletCodegen.OnChannelPaymentRequest)(
          tx.getTransaction
        )
        _ = require(
          requests.length == 1,
          s"Expected create payment request to create one requests, but found ${requests.length} requests: $requests",
        )
      } yield v0.CreateOnChannelPaymentRequestResponse(
        requestContractId = Proto.encode(requests(0).contractId)
      )
    }

  @nowarn("cat=unused")
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
          .exerciseOnChannelPaymentRequest_Accept(walletParty.toPrim, arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(validatorParty.get()),
          Seq(cmd),
        )
      } yield Empty()
    }

  @nowarn("cat=unused")
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
          .exerciseOnChannelPaymentRequest_Reject(walletParty.toPrim, arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
    }

  @nowarn("cat=unused")
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
          .exerciseOnChannelPaymentRequest_Withdraw(walletParty.toPrim, arg)
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
          .key(DA.Types.Tuple2(svcParty.toPrim, validatorParty.get.toPrim))
          .exerciseCoinRules_Transfer(
            party.toPrim,
            coinRulesCodegen.Transfer(
              sender = party.toPrim,
              inputs = inputs,
              outputs = outputs,
              payload = "redistribute",
            ),
          )
          .command
        tx <- connection.submitCommand(
          Seq(party),
          Seq(validatorParty.get()),
          Seq(cmd),
        )
        coins = DecodeUtil.decodeAllCreated(coinCodegen.Coin)(
          tx.getTransaction
        )
      } yield {
        v0.RedistributeResponse(
          coins.map(c => Proto.encode(c.contractId))
        )
      }
    }
  }

  override def initialize(request: InitializeRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => _ =>
      validatorParty.set(PartyId.tryFromProtoPrimitive(request.validatorPartyId))

      for {
        _ <- connection.uploadDarFile(
          WalletUtil
        ) // TODO(i353) move away from dar upload during init
      } yield Empty()
    }
}
