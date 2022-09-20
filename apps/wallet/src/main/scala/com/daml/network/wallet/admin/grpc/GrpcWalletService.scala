package com.daml.network.wallet.admin.grpc

import cats.implicits._
import com.daml.ledger.client.binding.{Primitive, Template, ValueDecoder}
import com.daml.network.codegen.CC.{Coin => coinCodegen, CoinRules => coinRulesCodegen}
import com.daml.network.codegen.CN.{Wallet => walletCodegen}
import com.daml.network.codegen.DA
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AppCoinStore
import com.daml.network.util.{CoinUtil, Contract, Proto, Value}
import com.daml.network.wallet.store.WalletAppRequestStore
import com.daml.network.wallet.util.WalletUtil
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{InitializeRequest, WalletServiceGrpc}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import java.util.concurrent.atomic.AtomicReference
import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

case class WalletServiceState(
    validatorParty: PartyId,
    walletServiceParty: PartyId,
)

class GrpcWalletService(
    coinStore: AppCoinStore,
    store: WalletAppRequestStore,
    ledgerClient: CoinLedgerClient,
    scanConnection: ScanConnection,
    walletServiceUser: String,
    protected val loggerFactory: NamedLoggerFactory,
)(implicit
    ec: ExecutionContext,
    tracer: Tracer,
) extends WalletServiceGrpc.WalletService
    with Spanning
    with NamedLogging {

  private val connection = ledgerClient.connection("GrpcWalletService")

  val state: AtomicReference[Option[WalletServiceState]] =
    new AtomicReference[Option[WalletServiceState]](None)
  private def getState: WalletServiceState =
    state.get.getOrElse(
      throw new StatusRuntimeException(
        Status.FAILED_PRECONDITION.withDescription(
          "Wallet is not initialized, run wallet.initialize(validatorParty) first"
        )
      )
    )

  def getValidatorParty: PartyId =
    getState.validatorParty

  def getWalletServiceParty: PartyId =
    getState.walletServiceParty

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
        coins <- coinStore.listCoins(walletParty)
      } yield {
        v0.ListResponse(coins.map(x => x.toProtoV0))
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
  override def listAppPaymentRequests(
      request: v0.ListAppPaymentRequestsRequest
  ): Future[v0.ListAppPaymentRequestsResponse] =
    withSpanFromGrpcContext("GrpcSvcAppService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
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

  override def getBalance(
      request: v0.GetBalanceRequest
  ): Future[v0.GetBalanceResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletParty <- connection.getPrimaryParty(request.getWalletCtx.userId)
        // TODO(i779) switch to wallet store query instead of ACS query
        coinsLAPI <- connection.activeContracts(walletParty, coinCodegen.Coin)
        lockedCoinsLAPI <- connection.activeContracts(walletParty, coinCodegen.LockedCoin)
        currentRound <- scanConnection.getCurrentRound()
      } yield {
        val unlockedHoldingFees =
          coinsLAPI.foldl(BigDecimal(0))((qty, coin) =>
            qty + CoinUtil.holdingFee(coin.value, currentRound)
          )

        val unlockedQty =
          coinsLAPI.foldl(BigDecimal(0))((qty, coin) =>
            qty + CoinUtil.currentQuantity(coin.value, currentRound)
          )

        val lockedQty = lockedCoinsLAPI.foldl(BigDecimal(0))((qty, coin) =>
          qty + CoinUtil.currentQuantity(coin.value.coin, currentRound)
        )

        v0.GetBalanceResponse(
          currentRound,
          Proto.encode(unlockedQty),
          Proto.encode(lockedQty),
          Proto.encode(unlockedHoldingFees),
        )
      }
    }

  override def acceptAppPaymentRequest(
      request: v0.AcceptAppPaymentRequestRequest
  ): Future[v0.AcceptAppPaymentRequestResponse] =
    exerciseWalletAction(implicit traceContext =>
      (c, party) => {
        for {
          requestC <- store.findAppPaymentRequest(party, request.requestContractId)
          quantity = requestC
            .getOrElse(
              sys.error(
                s"Could not find app payment request ${request.requestContractId}. " +
                  "Note that there is a very small delay before payment requests appear in the wallet. " +
                  "If you have recently received a request, retry the command."
              )
            )
            .payload
            .quantity
          coinCid <- selectCoin(party, quantity, store)
        } yield {
          val requestCid = Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](
            request.requestContractId
          )
          val transferInput = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
          c.exerciseWalletAppInstall_AcceptAppPaymentRequest(requestCid, transferInput)
        }
      }
    )(
      request.getWalletCtx,
      paymentCid =>
        v0.AcceptAppPaymentRequestResponse(
          Proto.encode(paymentCid)
        ),
    )

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
          Seq(getValidatorParty),
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
          Seq(getValidatorParty),
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
          coinCid <- selectCoin(party, quantity, store)
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

  @nowarn("cat=unused")
  override def listAppRewards(
      request: v0.ListAppRewardsRequest
  ): Future[v0.ListAppRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(request.getWalletCtx.userId)
        appRewards <- connection.activeContracts(party, coinCodegen.AppReward)
      } yield {
        val filtered = appRewards.filter(c => c.value.owner == party.toPrim)
        v0.ListAppRewardsResponse(
          filtered.map(c => Contract.fromCodegenContract(c).toProtoV0)
        )
      }
    }

  @nowarn("cat=unused")
  override def listValidatorRewards(
      request: v0.ListValidatorRewardsRequest
  ): Future[v0.ListValidatorRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        party <- connection.getPrimaryParty(request.getWalletCtx.userId)
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
      (c, party) => {
        for {
          requestC <- store.findOnChannelPaymentRequest(party, request.requestContractId)
          quantity = requestC
            .getOrElse(
              sys.error(
                s"Could not find on channel payment request ${request.requestContractId}. " +
                  "Note that there is a very small delay before payment requests appear in the wallet. " +
                  "If you have recently received a request, retry the command."
              )
            )
            .payload
            .quantity
          coinCid <- selectCoin(party, quantity, store)
        } yield {
          val requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
            request.requestContractId
          )
          val transferInput = Seq(coinRulesCodegen.TransferInput.InputCoin(coinCid))
          c.exerciseWalletAppInstall_AcceptOnChannelPaymentRequest(requestCid, transferInput)
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
      val validatorServiceParty = PartyId.tryFromProtoPrimitive(request.validatorPartyId)

      for {
        walletServiceParty <- connection.getPrimaryParty(walletServiceUser)
        _ <- WalletUtil.initializeWalletApp(
          walletServiceParty,
          validatorServiceParty,
          connection,
          logger,
        )
      } yield {
        state.set(
          Some(
            WalletServiceState(
              validatorParty = validatorServiceParty,
              walletServiceParty = walletServiceParty,
            )
          )
        )
        Empty()
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
          Template.Key[walletCodegen.WalletAppInstall],
          PartyId,
      ) => Future[Primitive.Update[ChoiceResult]]
  )(
      ctx: com.daml.network.wallet.v0.WalletContext,
      response: ChoiceResult => Response,
  )(implicit valueDecoder: ValueDecoder[ChoiceResult]): Future[Response] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        walletUserParty <- connection.getPrimaryParty(ctx.userId)
        installTemplate = walletCodegen.WalletAppInstall
          .key(DA.Types.Tuple2(walletUserParty.toPrim, getWalletServiceParty.toPrim))
        update <- choice(traceContext)(installTemplate, walletUserParty)
        result <- connection.submitWithResult(
          Seq(getWalletServiceParty),
          Seq(getValidatorParty, walletUserParty),
          update,
        )
      } yield response(result)
    }

  private def exerciseSyncWalletAction[Response, ChoiceResult](
      choice: TraceContext => (
          Template.Key[walletCodegen.WalletAppInstall],
          PartyId,
      ) => Primitive.Update[ChoiceResult]
  )(
      ctx: com.daml.network.wallet.v0.WalletContext,
      response: ChoiceResult => Response,
  )(implicit valueDecoder: ValueDecoder[ChoiceResult]): Future[Response] =
    exerciseWalletAction(tc => (t, p) => Future.successful(choice(tc)(t, p)))(ctx, response)

  @SuppressWarnings(Array("org.wartremover.warts.TraversableOps"))
  private def selectCoin(
      owner: PartyId,
      quantity: BigDecimal,
      store: WalletAppRequestStore,
  )(implicit tc: TraceContext): Future[Primitive.ContractId[coinCodegen.Coin]] = {
    for {
      currentRound <- scanConnection.getCurrentRound()
      coins <- coinStore.listCoins(owner)
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
