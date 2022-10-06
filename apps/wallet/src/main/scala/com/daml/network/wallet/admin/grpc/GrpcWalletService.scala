package com.daml.network.wallet.admin.grpc

import cats.implicits._
import com.daml.ledger.client.binding.{Primitive, ValueDecoder}
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
import com.daml.network.codegen.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.codegen.DA
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.{CoinUtil, Contract, Proto, Value}
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{CollectRewardsRequest, WalletServiceGrpc}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcWalletService(
    store: WalletStore,
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

  private val walletServiceParty: PartyId = store.key.walletServiceParty
  private val validatorParty: PartyId = store.key.validatorParty

  private val connection = ledgerClient.connection("GrpcWalletService")

  private def coinToCoinPosition(coin: Contract[Coin], round: Long): v0.CoinPosition =
    v0.CoinPosition(
      Some(coin.toProtoV0),
      round,
      Proto.encode(CoinUtil.holdingFee(coin.payload, round)),
      Proto.encode(CoinUtil.currentQuantity(coin.payload, round)),
    )

  private def lockedCoinToCoinPosition(
      lockedCoin: Contract[LockedCoin],
      round: Long,
  ): v0.CoinPosition =
    v0.CoinPosition(
      Some(lockedCoin.toProtoV0),
      round,
      Proto.encode(CoinUtil.holdingFee(lockedCoin.payload.coin, round)),
      Proto.encode(CoinUtil.currentQuantity(lockedCoin.payload.coin, round)),
    )

  private[this] def getUserStore(ctxt: v0.WalletContext): Future[EndUserWalletStore] = {
    // TODO(#990): replace this call
    connection
      .getPrimaryParty(ctxt.userId)
      .map(userParty => getUserStore(userParty))
  }

  private[this] def getUserStore(userParty: PartyId): EndUserWalletStore =
    store
      .lookupEndUserStore(userParty)
      .getOrElse(
        throw new StatusRuntimeException(Status.NOT_FOUND.withDescription(s"End-user $userParty"))
      )

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        currentRound <- scanConnection.getCurrentRound()
        QueryResult(_, coins) <- userStore.listCoins()
        QueryResult(_, lockedCoins) <- userStore.listLockedCoins()
      } yield {
        v0.ListResponse(
          coins.map(coinToCoinPosition(_, currentRound)),
          lockedCoins.map(lockedCoinToCoinPosition(_, currentRound)),
        )
      }
    }

  override def tap(request: v0.TapRequest): Future[v0.TapResponse] =
    exerciseSyncWalletAction(_ =>
      (c, _) => {
        val quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        c.exerciseWalletAppInstall_Tap(quantity)
      }
    )(request.getWalletCtx, coinCid => v0.TapResponse(Proto.encode(coinCid)))

  @nowarn("cat=unused")
  override def listAppMultiPaymentRequests(
      request: v0.ListAppMultiPaymentRequestsRequest
  ): Future[v0.ListAppMultiPaymentRequestsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        result <- userStore.listAppMultiPaymentRequests()
      } yield v0.ListAppMultiPaymentRequestsResponse(result.value.map(_.toProtoV0))
    }

  @nowarn("cat=unused")
  override def listAppPaymentRequests(
      request: v0.ListAppPaymentRequestsRequest
  ): Future[v0.ListAppPaymentRequestsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        result <- userStore.listAppPaymentRequests()
      } yield v0.ListAppPaymentRequestsResponse(result.value.map(_.toProtoV0))
    }

  override def getBalance(
      request: v0.GetBalanceRequest
  ): Future[v0.GetBalanceResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        QueryResult(_, coins) <- userStore.listCoins()
        QueryResult(_, lockedCoins) <- userStore.listLockedCoins()
        currentRound <- scanConnection.getCurrentRound()
      } yield {
        val unlockedHoldingFees =
          coins.view.map(c => CoinUtil.holdingFee(c.payload, currentRound)).sum
        val unlockedQty =
          coins.view.map(c => CoinUtil.currentQuantity(c.payload, currentRound)).sum
        val lockedQty =
          lockedCoins.view.map(c => CoinUtil.currentQuantity(c.payload.coin, currentRound)).sum

        v0.GetBalanceResponse(
          currentRound,
          Proto.encode(unlockedQty),
          Proto.encode(lockedQty),
          Proto.encode(unlockedHoldingFees),
        )
      }
    }

  override def acceptAppMultiPaymentRequest(
      request: v0.AcceptAppMultiPaymentRequestRequest
  ): Future[v0.AcceptAppMultiPaymentRequestResponse] =
    exerciseWalletAction(implicit traceContext =>
      (installCid, userStore) => {
        val requestCid = Proto.tryDecodeContractId[walletCodegen.AppMultiPaymentRequest](
          request.requestContractId
        )
        for {
          requestC <- userStore.lookupAppMultiPaymentRequestById(requestCid)
          quantity = requestC.value
            .getOrElse(
              sys.error(
                s"Could not find app multi-payment request ${request.requestContractId}. " +
                  "Note that there is a very small delay before payment requests appear in the wallet. " +
                  "If you have recently received a request, retry the command."
              )
            )
            .payload
            .receiverQuantities
            .map(_.quantity)
            .sum
          coinCid <- selectCoin(userStore, quantity)
        } yield {
          val transferInput = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
          installCid.exerciseWalletAppInstall_AcceptAppMultiPaymentRequest(
            requestCid,
            transferInput,
          )
        }
      }
    )(
      request.getWalletCtx,
      paymentCid =>
        v0.AcceptAppMultiPaymentRequestResponse(
          Proto.encode(paymentCid)
        ),
    )

  override def acceptAppPaymentRequest(
      request: v0.AcceptAppPaymentRequestRequest
  ): Future[v0.AcceptAppPaymentRequestResponse] =
    exerciseWalletAction(implicit traceContext =>
      (installCid, userStore) => {
        val requestCid = Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](
          request.requestContractId
        )
        for {
          requestC <- userStore.lookupAppPaymentRequestById(requestCid)
          quantity = requestC.value
            .getOrElse(
              sys.error(
                s"Could not find app payment request ${request.requestContractId}. " +
                  "Note that there is a very small delay before payment requests appear in the wallet. " +
                  "If you have recently received a request, retry the command."
              )
            )
            .payload
            .quantity
          coinCid <- selectCoin(userStore, quantity)
        } yield {
          val transferInput = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
          installCid.exerciseWalletAppInstall_AcceptAppPaymentRequest(requestCid, transferInput)
        }
      }
    )(
      request.getWalletCtx,
      paymentCid =>
        v0.AcceptAppPaymentRequestResponse(
          Proto.encode(paymentCid)
        ),
    )

  override def rejectAppMultiPaymentRequest(
      request: v0.RejectAppMultiPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
        arg = walletCodegen.AppMultiPaymentRequest_Reject()
        requestCid = Proto.tryDecodeContractId[walletCodegen.AppMultiPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseAppMultiPaymentRequest_Reject(arg)
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def rejectAppPaymentRequest(
      request: v0.RejectAppPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
  override def listAcceptedAppMultiPayments(
      request: v0.ListAcceptedAppMultiPaymentsRequest
  ): Future[v0.ListAcceptedAppMultiPaymentsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(request.getWalletCtx.userId)
        acceptedAppMultiPayments <- connection.activeContracts(
          party,
          walletCodegen.AcceptedAppMultiPayment,
        )
      } yield {
        val filtered = acceptedAppMultiPayments.filter(c => c.value.sender == party.toPrim)
        v0.ListAcceptedAppMultiPaymentsResponse(
          filtered.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  @nowarn("cat=unused")
  override def listAcceptedAppPayments(
      request: v0.ListAcceptedAppPaymentsRequest
  ): Future[v0.ListAcceptedAppPaymentsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
      request: v0.ListPaymentChannelProposalsRequest
  ): Future[v0.ListPaymentChannelProposalsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
      request: v0.ListPaymentChannelsRequest
  ): Future[v0.ListPaymentChannelsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
        svcParty <- scanConnection.getSvcPartyId()
        // TODO(Mx-90): guard making the proposal by a check that a like channel does not yet exist
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
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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

  override def cancelPaymentChannelBySender(
      request: v0.CancelPaymentChannelBySenderRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
        senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
        cmd = walletCodegen.PaymentChannel
          .key(DA.Types.Tuple3(senderParty.toPrim, walletParty.toPrim, svcParty.toPrim))
          .exercisePaymentChannel_Cancel_By_Sender()
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(validatorParty),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def cancelPaymentChannelByReceiver(
      request: v0.CancelPaymentChannelByReceiverRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
        receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
        cmd = walletCodegen.PaymentChannel
          .key(DA.Types.Tuple3(walletParty.toPrim, receiverParty.toPrim, svcParty.toPrim))
          .exercisePaymentChannel_Cancel_By_Receiver()
          .command
        _ <- connection.submitCommand(
          Seq(walletParty),
          Seq(validatorParty),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def executeDirectTransfer(
      request: v0.ExecuteDirectTransferRequest
  ): Future[Empty] =
    exerciseWalletAction(implicit traceContext =>
      (c, party) => {
        val quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        for {
          coinCid <- selectCoin(party, quantity)
        } yield {
          val receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          val arg = walletCodegen.WalletAppInstall_ExecuteDirectTransfer(
            receiver = receiverParty.toPrim,
            inputs = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid)),
            quantity = quantity,
            payload = "wallet: execute direct transfer",
          )
          c.exerciseWalletAppInstall_ExecuteDirectTransfer(arg)
        }
      }
    )(
      request.getWalletCtx,
      _ => Empty(),
    )

  private def listAppRewards(
      party: PartyId
  ) =
    for {
      appRewards <- connection.activeContracts(party, coinCodegen.AppReward)
    } yield {
      appRewards.filter(c => c.value.provider == party.toPrim)
    }

  @nowarn("cat=unused")
  override def listAppRewards(
      request: v0.ListAppRewardsRequest
  ): Future[v0.ListAppRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(request.getWalletCtx.userId)
        appRewards <- listAppRewards(party)
      } yield {
        v0.ListAppRewardsResponse(
          appRewards.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  private def listValidatorRewards(
      party: PartyId
  ) = for {
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
  } yield validatorRewards

  @nowarn("cat=unused")
  override def listValidatorRewards(
      request: v0.ListValidatorRewardsRequest
  ): Future[v0.ListValidatorRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(request.getWalletCtx.userId)
        validatorRewards <- listValidatorRewards(party)
      } yield {
        v0.ListValidatorRewardsResponse(
          validatorRewards.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  override def collectRewards(request: CollectRewardsRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        userParty = userStore.key.endUserParty
        validatorRewards <- listValidatorRewards(userParty)
        validatorRewardInputs = validatorRewards
          .filter(c => c.value.round.number == request.round)
          .map(c => coinRulesCodegen.TransferInput.InputValidatorReward(c.contractId))
        appRewards <- userStore.listAppRewards()
        appRewardInputs = appRewards.value
          .filter(c => c.payload.round.number == request.round)
          .map(c => coinRulesCodegen.TransferInput.InputAppReward(c.contractId))
        coinCid <- selectCoin(userStore, 0)
        inputCoin = coinRulesCodegen.TransferInput.InputCoin(coinCid)
        inputs = (inputCoin +: validatorRewardInputs :++ appRewardInputs)
        outputs = Seq(
          coinRulesCodegen.TransferOutput.OutputSenderCoin(exactQuantity = None, lock = None)
        )
        coins <- redistribute(userParty, inputs, outputs)
      } yield {
        require(coins.size == 1, "Expected exactly one coin")
        Empty()
      }
    }

  override def createOnChannelPaymentRequest(
      request: v0.CreateOnChannelPaymentRequestRequest
  ): Future[v0.CreateOnChannelPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        svcParty <- scanConnection.getSvcPartyId()
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
      request: v0.ListOnChannelPaymentRequestsRequest
  ): Future[v0.ListOnChannelPaymentRequestsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
    exerciseWalletAction(implicit traceContext =>
      (installCid, userStore) => {
        val requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )
        for {
          requestC <- userStore.lookupOnChannelPaymentRequestById(requestCid)
          quantity = requestC.value
            .getOrElse(
              sys.error(
                s"Could not find on channel payment request ${request.requestContractId}. " +
                  "Note that there is a very small delay before payment requests appear in the wallet. " +
                  "If you have recently received a request, retry the command."
              )
            )
            .payload
            .quantity
          coinCid <- selectCoin(userStore, quantity)
        } yield {
          val transferInput = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
          installCid.exerciseWalletAppInstall_AcceptOnChannelPaymentRequest(
            requestCid,
            transferInput,
          )
        }
      }
    )(
      request.getWalletCtx,
      _ => Empty(),
    )

  override def rejectOnChannelPaymentRequest(
      request: v0.RejectOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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

  private def redistribute(
      party: PartyId,
      inputs: Seq[coinRulesCodegen.TransferInput],
      outputs: Seq[coinRulesCodegen.TransferOutput],
  )(implicit tc: TraceContext) = {
    for {
      svcParty <- scanConnection.getSvcPartyId()
      cmd = coinRulesCodegen.CoinRules
        .key(DA.Types.Tuple2(svcParty.toPrim, validatorParty.toPrim))
        .exerciseCoinRules_Transfer(
          coinRulesCodegen.Transfer(
            sender = party.toPrim,
            provider = party.toPrim,
            inputs = inputs,
            outputs = outputs,
            payload = "redistribute",
          )
        )
      transferResults <- connection.submitWithResult(
        Seq(party),
        Seq(validatorParty),
        cmd,
      )
    } yield {
      transferResults.createdCoins.collect {
        case coinRulesCodegen.CreatedCoin.TransferResultCoin(cid) => cid
      }
    }
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
        party <- connection.getPrimaryParty(request.getWalletCtx.userId)
        inputs = request.inputs
          .traverse(Value.fromProto[coinRulesCodegen.TransferInput](_).map(_.value))
          .valueOr(err => throw err.toAdminError.asGrpcError)
        outputs = request.outputs.map(toOutput)
        createdCoins <- redistribute(party, inputs, outputs)
      } yield {
        v0.RedistributeResponse(createdCoins.map(cid => Proto.encode(cid)))
      }
    }
  }

  /** Executes a wallet action by calling a choice on the WalletAppInstall contract of the
    * end user specified in the given WalletContext.
    *
    * The choice is always executed with the wallet service party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Additionally, the validator service party is also a readAs party (workaround for lack
    * of explicit disclosure for CoinRules).
    *
    * Note: curried syntax helps with type inference
    */
  private def exerciseWalletAction[Response, ChoiceResult](
      choice: TraceContext => (
          Primitive.ContractId[walletCodegen.WalletAppInstall],
          EndUserWalletStore,
      ) => Future[Primitive.Update[ChoiceResult]]
  )(
      ctx: com.daml.network.wallet.v0.WalletContext,
      response: ChoiceResult => Response,
  )(implicit valueDecoder: ValueDecoder[ChoiceResult]): Future[Response] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      getUserStore(ctx).flatMap(userStore => {
        val userParty = userStore.key.endUserParty
        store.lookupInstall(userParty).flatMap {
          case QueryResult(_, None) =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(s"WalletAppInstall contrat of user $userParty")
            )
          case QueryResult(_, Some(install)) =>
            choice(traceContext)(install.contractId, userStore).flatMap(update =>
              connection
                .submitWithResult(
                  Seq(walletServiceParty),
                  Seq(validatorParty, userParty),
                  update,
                )
                .map(response)
            )
        }
      })
    }

  private def exerciseSyncWalletAction[Response, ChoiceResult](
      choice: TraceContext => (
          Primitive.ContractId[walletCodegen.WalletAppInstall],
          EndUserWalletStore,
      ) => Primitive.Update[ChoiceResult]
  )(
      ctx: com.daml.network.wallet.v0.WalletContext,
      response: ChoiceResult => Response,
  )(implicit valueDecoder: ValueDecoder[ChoiceResult]): Future[Response] =
    exerciseWalletAction(tc => (cid, userStore) => Future.successful(choice(tc)(cid, userStore)))(
      ctx,
      response,
    )

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def selectCoin(
      userStore: EndUserWalletStore,
      quantity: BigDecimal,
  )(implicit tc: TraceContext): Future[Primitive.ContractId[coinCodegen.Coin]] = {
    val owner = userStore.key.endUserParty
    for {
      currentRound <- scanConnection.getCurrentRound()
      QueryResult(_, coins) <- userStore.listCoins()
      candidates = coins
        .filter(c => CoinUtil.currentQuantity(c.payload, currentRound) >= quantity)
    } yield {
      logger.debug(
        s"Selecting a coin for $owner with a remaining quantity of $quantity in round $currentRound. " +
          s"There are ${coins.length} coins, ${candidates.length} of which are big enough."
      )
      if (coins.isEmpty) {
        throw new RuntimeException(
          s"Party $owner has no coins. " +
            "Note that there is a very small delay before coins appear in the wallet. " +
            "If you have recently acquired some coins, retry the command."
        )
      } else if (candidates.isEmpty) {
        throw new RuntimeException(
          s"Party $owner has ${coins.length} coins, but none of them have enough remaining quantity." +
            "Note that there is a very small delay before coins appear in the wallet. " +
            "If you have recently acquired some coins, retry the command."
        )
      } else {
        // Use the coin that is most expensive to hold
        candidates.maxBy(_.payload.quantity.ratePerRound.rate).contractId
      }
    }
  }
}
