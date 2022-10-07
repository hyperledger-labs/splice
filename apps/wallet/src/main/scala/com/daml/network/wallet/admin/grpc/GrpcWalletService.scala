package com.daml.network.wallet.admin.grpc

import cats.implicits._
import com.daml.ledger.client.binding.{Primitive, TemplateCompanion, ValueDecoder}
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
import com.daml.network.{v0 => networkV0}
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil._
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

  @nowarn("cat=unused")
  override def userStatus(request: v0.UserStatusRequest): Future[v0.UserStatusResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        QueryResult(_, optInstall) <- store.lookupInstallByName(request.getWalletCtx.userName)
      } yield {
        v0.UserStatusResponse(
          partyId = optInstall.fold("")(co => Primitive.Party.unwrap(co.payload.endUserParty)),
          userOnboarded = optInstall.isDefined,
        )
      }
    }

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

  private[this] def getUserStore(ctxt: v0.WalletContext): Future[EndUserWalletStore] = Future {
    store
      .lookupEndUserStore(ctxt.userName)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"User ${ctxt.userName.singleQuoted}")
        )
      )
  }

  private def listContracts[T, ResponseT](
      context: v0.WalletContext,
      templateCompanion: TemplateCompanion[T],
      mkResponse: Seq[networkV0.Contract] => ResponseT,
  ): Future[ResponseT] =
    for {
      userStore <- getUserStore(context)
      QueryResult(_, contracts) <- userStore.listContracts(templateCompanion)
    } yield mkResponse(contracts.map(_.toProtoV0))

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        currentRound <- scanConnection.getCurrentRound()
        QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin)
        QueryResult(_, lockedCoins) <- userStore.listContracts(coinCodegen.LockedCoin)
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

  override def listAppMultiPaymentRequests(
      request: v0.ListAppMultiPaymentRequestsRequest
  ): Future[v0.ListAppMultiPaymentRequestsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.AppMultiPaymentRequest,
      v0.ListAppMultiPaymentRequestsResponse(_),
    )

  override def listAppPaymentRequests(
      request: v0.ListAppPaymentRequestsRequest
  ): Future[v0.ListAppPaymentRequestsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.AppPaymentRequest,
      v0.ListAppPaymentRequestsResponse(_),
    )

  override def getBalance(
      request: v0.GetBalanceRequest
  ): Future[v0.GetBalanceResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin)
        QueryResult(_, lockedCoins) <- userStore.listContracts(coinCodegen.LockedCoin)
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
        userStore <- getUserStore(request.getWalletCtx)
        arg = walletCodegen.AppMultiPaymentRequest_Reject()
        requestCid = Proto.tryDecodeContractId[walletCodegen.AppMultiPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseAppMultiPaymentRequest_Reject(arg)
          .command
        _ <- connection.submitCommand(
          Seq(userStore.key.endUserParty),
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
        userStore <- getUserStore(request.getWalletCtx)
        arg = walletCodegen.AppPaymentRequest_Reject()
        requestCid = Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseAppPaymentRequest_Reject(arg)
          .command
        _ <- connection.submitCommand(
          Seq(userStore.key.endUserParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
    }

  override def listAcceptedAppMultiPayments(
      request: v0.ListAcceptedAppMultiPaymentsRequest
  ): Future[v0.ListAcceptedAppMultiPaymentsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.AcceptedAppMultiPayment,
      v0.ListAcceptedAppMultiPaymentsResponse(_),
    )

  override def listAcceptedAppPayments(
      request: v0.ListAcceptedAppPaymentsRequest
  ): Future[v0.ListAcceptedAppPaymentsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.AcceptedAppPayment,
      v0.ListAcceptedAppPaymentsResponse(_),
    )

  override def listPaymentChannelProposals(
      request: v0.ListPaymentChannelProposalsRequest
  ): Future[v0.ListPaymentChannelProposalsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.PaymentChannelProposal,
      v0.ListPaymentChannelProposalsResponse(_),
    )

  override def listPaymentChannels(
      request: v0.ListPaymentChannelsRequest
  ): Future[v0.ListPaymentChannelsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.PaymentChannel,
      v0.ListPaymentChannelsResponse(_),
    )

  override def proposePaymentChannel(
      request: v0.ProposePaymentChannelRequest
  ): Future[v0.ProposePaymentChannelResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        svcParty = userStore.key.svcParty
        // TODO(Mx-90): guard making the proposal by a check that a like channel does not yet exist
        receiver = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
        cmd = walletCodegen
          .PaymentChannelProposal(
            proposer = userStore.key.endUserParty.toPrim,
            channel = walletCodegen.PaymentChannel(
              sender = userStore.key.endUserParty.toPrim,
              receiver = receiver.toPrim,
              svc = svcParty.toPrim,
              allowRequests = request.allowRequests,
              allowOffers = request.allowOffers,
              allowDirectTransfers = request.allowDirectTransfers,
              senderTransferFeeRatio =
                Proto.tryDecode(Proto.BigDecimal)(request.senderTransferFeeRatio),
            ),
            replacesChannel = request.replacesChannelId.map(
              Proto.tryDecodeContractId[walletCodegen.PaymentChannel]
            ),
          )
          .create
        proposalCid <- connection.submitWithResult(
          Seq(userStore.key.endUserParty),
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
        userStore <- getUserStore(request.getWalletCtx)
        arg = walletCodegen.PaymentChannelProposal_Accept()
        // TODO(M3-01): guard accepting the proposal by a check that a channel with the same key does not yet exist
        proposalCid = Proto.tryDecodeContractId[walletCodegen.PaymentChannelProposal](
          request.proposalContractId
        )
        cmd = proposalCid
          .exercisePaymentChannelProposal_Accept(arg)
        channelCid <- connection.submitWithResult(
          Seq(userStore.key.endUserParty),
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
        userStore <- getUserStore(request.getWalletCtx)
        svcParty = userStore.key.svcParty
        senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
        cmd = walletCodegen.PaymentChannel
          .key(
            DA.Types.Tuple3(senderParty.toPrim, userStore.key.endUserParty.toPrim, svcParty.toPrim)
          )
          .exercisePaymentChannel_Cancel_By_Sender()
          .command
        _ <- connection.submitCommand(
          Seq(userStore.key.endUserParty),
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
        userStore <- getUserStore(request.getWalletCtx)
        svcParty = userStore.key.svcParty
        receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
        cmd = walletCodegen.PaymentChannel
          .key(
            DA.Types
              .Tuple3(userStore.key.endUserParty.toPrim, receiverParty.toPrim, svcParty.toPrim)
          )
          .exercisePaymentChannel_Cancel_By_Receiver()
          .command
        _ <- connection.submitCommand(
          Seq(userStore.key.endUserParty),
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

  override def listAppRewards(
      request: v0.ListAppRewardsRequest
  ): Future[v0.ListAppRewardsResponse] =
    listContracts(
      request.getWalletCtx,
      coinCodegen.AppReward,
      v0.ListAppRewardsResponse(_),
    )

  // TODO(M1-52): this function probably needs restructuring to integrate it with automation rewards collection; e.g., move it to the stores and make it streaming
  private def listValidatorRewardsCollectableBy(
      validatorUserStore: EndUserWalletStore
  )(implicit tc: TraceContext): Future[Seq[Contract[coinCodegen.ValidatorReward]]] = for {
    QueryResult(_, validatorRights) <- validatorUserStore.listContracts(coinCodegen.ValidatorRight)
    users = validatorRights.map(c => PartyId.tryFromPrim(c.payload.user)).toSet
    validatorRewardsFs: Seq[Future[Seq[Contract[coinCodegen.ValidatorReward]]]] = users.toSeq
      .map(u =>
        store.lookupInstallByParty(u).flatMap {
          case QueryResult(_, None) =>
            logger.warn(
              s"ValidatorRight of ${validatorUserStore.key.endUserParty} for end-user party $u has no associated WalletAppInstall contract."
            )
            Future.successful(Seq.empty)
          case QueryResult(_, Some(install)) =>
            store.lookupEndUserStore(install.payload.endUserName) match {
              case None =>
                logger.warn(
                  s"Might miss validator rewards as the EndUserWalletStore for end-user name ${install.payload.endUserName} is not (yet) setup."
                )
                Future.successful(Seq.empty)
              case Some(userStore) =>
                userStore.listContracts(coinCodegen.ValidatorReward).map(_.value)
            }
        }
      )
    validatorRewards <- Future.sequence(validatorRewardsFs)
  } yield validatorRewards.flatten

  override def listValidatorRewards(
      request: v0.ListValidatorRewardsRequest
  ): Future[v0.ListValidatorRewardsResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        validatorRewards <- listValidatorRewardsCollectableBy(userStore)
      } yield v0.ListValidatorRewardsResponse(validatorRewards.map(_.toProtoV0))
    }

  override def collectRewards(request: CollectRewardsRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(request.getWalletCtx)
        validatorRewards <- listValidatorRewardsCollectableBy(userStore)
        validatorRewardInputs = validatorRewards
          .filter(c => c.payload.round.number == request.round)
          .map(c => coinRulesCodegen.TransferInput.InputValidatorReward(c.contractId))
        QueryResult(_, appRewards) <- userStore.listContracts(coinCodegen.AppReward)
        appRewardInputs = appRewards
          .filter(c => c.payload.round.number == request.round)
          .map(c => coinRulesCodegen.TransferInput.InputAppReward(c.contractId))
        coinCid <- selectCoin(userStore, 0)
        inputCoin = coinRulesCodegen.TransferInput.InputCoin(coinCid)
        inputs = (inputCoin +: validatorRewardInputs :++ appRewardInputs)
        outputs = Seq(
          coinRulesCodegen.TransferOutput.OutputSenderCoin(exactQuantity = None, lock = None)
        )
        coins <- redistribute(userStore, inputs, outputs)
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
        userStore <- getUserStore(request.getWalletCtx)
        svcParty = userStore.key.svcParty
        senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
        quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        arg = walletCodegen.PaymentChannel_CreatePaymentRequest(
          quantity = quantity,
          description = request.description,
        )
        cmd = walletCodegen.PaymentChannel
          .key(
            DA.Types.Tuple3(senderParty.toPrim, userStore.key.endUserParty.toPrim, svcParty.toPrim)
          )
          .exercisePaymentChannel_CreatePaymentRequest(arg)
        requestCid <- connection.submitWithResult(
          Seq(userStore.key.endUserParty),
          Seq(),
          cmd,
        )
      } yield v0.CreateOnChannelPaymentRequestResponse(
        requestContractId = Proto.encode(requestCid)
      )
    }

  override def listOnChannelPaymentRequests(
      request: v0.ListOnChannelPaymentRequestsRequest
  ): Future[v0.ListOnChannelPaymentRequestsResponse] =
    listContracts(
      request.getWalletCtx,
      walletCodegen.OnChannelPaymentRequest,
      v0.ListOnChannelPaymentRequestsResponse(_),
    )

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
        userStore <- getUserStore(request.getWalletCtx)
        arg = walletCodegen.OnChannelPaymentRequest_Reject()
        requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseOnChannelPaymentRequest_Reject(arg)
          .command
        _ <- connection.submitCommand(
          Seq(userStore.key.endUserParty),
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
        userStore <- getUserStore(request.getWalletCtx)
        arg = walletCodegen.OnChannelPaymentRequest_Withdraw()
        requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )
        cmd = requestCid
          .exerciseOnChannelPaymentRequest_Withdraw(arg)
          .command
        _ <- connection.submitCommand(
          Seq(userStore.key.endUserParty),
          Seq(),
          Seq(cmd),
        )
      } yield Empty()
    }

  private def redistribute(
      userStore: EndUserWalletStore,
      inputs: Seq[coinRulesCodegen.TransferInput],
      outputs: Seq[coinRulesCodegen.TransferOutput],
  )(implicit tc: TraceContext) = {
    val svcParty = userStore.key.svcParty
    val party = userStore.key.endUserParty
    val cmd = coinRulesCodegen.CoinRules
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
    for {
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
        userStore <- getUserStore(request.getWalletCtx)
        inputs = request.inputs
          .traverse(Value.fromProto[coinRulesCodegen.TransferInput](_).map(_.value))
          .valueOr(err => throw err.toAdminError.asGrpcError)
        outputs = request.outputs.map(toOutput)
        createdCoins <- redistribute(userStore, inputs, outputs)
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
        userStore.lookupInstall().flatMap {
          case QueryResult(_, None) =>
            throw new StatusRuntimeException(
              Status.NOT_FOUND.withDescription(s"WalletAppInstall contract of user $userParty")
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
      QueryResult(_, coins) <- userStore.listContracts(coinCodegen.Coin)
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
