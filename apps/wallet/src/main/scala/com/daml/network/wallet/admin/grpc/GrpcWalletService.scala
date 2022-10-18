package com.daml.network.wallet.admin.grpc

import cats.implicits.*
import com.daml.ledger.client.binding.{Primitive, TemplateCompanion}
import com.daml.network.auth.AuthInterceptor
import com.daml.network.codegen.CC.Coin.{Coin, LockedCoin}
import com.daml.network.codegen.CC.{Coin as coinCodegen, CoinRules as coinRulesCodegen}
import com.daml.network.codegen.CN.Wallet.CoinOperationOutcome.COO_AcceptedAppMultiPayment
import com.daml.network.codegen.CN.Wallet.{CoinOperation, CoinOperationOutcome}
import com.daml.network.codegen.CN.Wallet as walletCodegen
import com.daml.network.codegen.DA
import com.daml.network.environment.CoinLedgerClient
import com.daml.network.scan.admin.api.client.ScanConnection
import com.daml.network.store.AcsStore.QueryResult
import com.daml.network.util.{CoinUtil, Contract, Proto, Value}
import com.daml.network.wallet.store.{EndUserWalletStore, WalletStore}
import com.daml.network.wallet.treasury.TreasuryServices
import com.daml.network.wallet.v0
import com.daml.network.wallet.v0.{CollectRewardsRequest, WalletServiceGrpc}
import com.daml.network.v0 as networkV0
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.tracing.{Spanning, TraceContext}
import com.digitalasset.canton.util.ShowUtil.*
import com.google.protobuf.empty.Empty
import io.grpc.{Status, StatusRuntimeException}
import io.opentelemetry.api.trace.Tracer

import scala.annotation.nowarn
import scala.concurrent.{ExecutionContext, Future}

class GrpcWalletService(
    store: WalletStore,
    treasuries: TreasuryServices,
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

  private def withAuth[A](ctx: v0.WalletContext)(f: String => A): A = {
    // TODO(i1012) - remove wallet context and enforce auth token
    f(AuthInterceptor.extractSubjectFromContext().getOrElse(ctx.userName))
  }

  private[this] def getUserStore(user: String): Future[EndUserWalletStore] = Future {
    store
      .lookupEndUserStore(user)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"User ${user.singleQuoted}")
        )
      )
  }

  private[this] def getUserTreasury(user: String): Future[EndUserTreasuryService] = Future {
    treasuries
      .lookupEndUserTreasury(user)
      .getOrElse(
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"User ${user.singleQuoted}")
        )
      )
  }

  private def listContracts[T, ResponseT](
      context: v0.WalletContext,
      templateCompanion: TemplateCompanion[T],
      mkResponse: Seq[networkV0.Contract] => ResponseT,
  ): Future[ResponseT] = withAuth(context) { user =>
    for {
      userStore <- getUserStore(user)
      QueryResult(_, contracts) <- userStore.listContracts(templateCompanion)
    } yield mkResponse(contracts.map(_.toProtoV0))
  }

  override def list(request: v0.ListRequest): Future[v0.ListResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def tap(request: v0.TapRequest): Future[v0.TapResponse] = {
    withAuth(request.getWalletCtx) { user =>
      exerciseWalletAction(implicit tc =>
        (c, endUserWalletStore) => {
          val quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
          for {
            svcParty <- scanConnection.getSvcPartyId()
          } yield {
            CoinOperation.CO_Tap(
              svcParty.toPrim,
              validatorParty.toPrim,
              endUserWalletStore.key.endUserParty.toPrim,
              quantity,
            )
          }
        }
      )(
        user,
        {
          case outcome: CoinOperationOutcome.COO_Tap =>
            v0.TapResponse(Proto.encode(outcome.body))
          case other => sys.error(s"unexpected coin operation outcome: $other")
        },
      )
    }
  }

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
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def acceptAppMultiPaymentRequest(
      request: v0.AcceptAppMultiPaymentRequestRequest
  ): Future[v0.AcceptAppMultiPaymentRequestResponse] = withAuth(request.getWalletCtx) { user =>
    exerciseWalletAction(_ =>
      (installCid, userStore) => {
        val requestCid = Proto.tryDecodeContractId[walletCodegen.AppMultiPaymentRequest](
          request.requestContractId
        )
        Future.successful(CoinOperation.CO_AppMultiPayment(requestCid))
      }
    )(
      user,
      {
        case outcome: COO_AcceptedAppMultiPayment =>
          v0.AcceptAppMultiPaymentRequestResponse(
            Proto.encode(outcome.body)
          )
        case other => sys.error(s"unexpected coin operation outcome: $other")
      },
    )
  }

  override def acceptAppPaymentRequest(
      request: v0.AcceptAppPaymentRequestRequest
  ): Future[v0.AcceptAppPaymentRequestResponse] = withAuth(request.getWalletCtx) { user =>
    exerciseWalletAction(traceContext =>
      (installCid, userStore) => {
        val requestCid =
          Proto.tryDecodeContractId[walletCodegen.AppPaymentRequest](request.requestContractId)
        Future.successful(CoinOperation.CO_AppPayment(requestCid))
      }
    )(
      user,
      {
        case outcome: CoinOperationOutcome.COO_AcceptedAppPayment =>
          v0.AcceptAppPaymentRequestResponse(
            Proto.encode(outcome.body)
          )
        case other => sys.error(s"unexpected coin operation outcome: $other")
      },
    )
  }

  override def rejectAppMultiPaymentRequest(
      request: v0.RejectAppMultiPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def rejectAppPaymentRequest(
      request: v0.RejectAppPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def acceptPaymentChannelProposal(
      request: v0.AcceptPaymentChannelProposalRequest
  ): Future[v0.AcceptPaymentChannelProposalResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def cancelPaymentChannelBySender(
      request: v0.CancelPaymentChannelBySenderRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          svcParty = userStore.key.svcParty
          senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
          cmd = walletCodegen.PaymentChannel
            .key(
              DA.Types
                .Tuple3(senderParty.toPrim, userStore.key.endUserParty.toPrim, svcParty.toPrim)
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
    }

  override def cancelPaymentChannelByReceiver(
      request: v0.CancelPaymentChannelByReceiverRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def executeDirectTransfer(
      request: v0.ExecuteDirectTransferRequest
  ): Future[Empty] = withAuth(request.getWalletCtx) { user =>
    exerciseWalletAction(implicit traceContext =>
      (c, userStore) => {
        val quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
        for {
          svcUser <- scanConnection.getSvcPartyId()
        } yield {
          val receiverParty = Proto.tryDecode(Proto.Party)(request.receiverPartyId)
          CoinOperation.CO_ChannelTransfer(
            userStore.key.endUserParty.toPrim,
            receiverParty.toPrim,
            svcUser.toPrim,
            quantity,
            "wallet: execute direct transfer",
          )
        }
      }
    )(
      user,
      _ => Empty(),
    )
  }

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
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          validatorRewards <- listValidatorRewardsCollectableBy(userStore)
        } yield v0.ListValidatorRewardsResponse(validatorRewards.map(_.toProtoV0))
      }
    }

  // TODO(#756) - Make this a `CoinOperation` too
  override def collectRewards(request: CollectRewardsRequest): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def createOnChannelPaymentRequest(
      request: v0.CreateOnChannelPaymentRequestRequest
  ): Future[v0.CreateOnChannelPaymentRequestResponse] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
          svcParty = userStore.key.svcParty
          senderParty = Proto.tryDecode(Proto.Party)(request.senderPartyId)
          quantity = Proto.tryDecode(Proto.BigDecimal)(request.quantity)
          arg = walletCodegen.PaymentChannel_CreatePaymentRequest(
            quantity = quantity,
            description = request.description,
          )
          cmd = walletCodegen.PaymentChannel
            .key(
              DA.Types
                .Tuple3(senderParty.toPrim, userStore.key.endUserParty.toPrim, svcParty.toPrim)
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
  ): Future[Empty] = withAuth(request.getWalletCtx) { user =>
    exerciseWalletAction(traceContext =>
      (installCid, userStore) => {
        val requestCid = Proto.tryDecodeContractId[walletCodegen.OnChannelPaymentRequest](
          request.requestContractId
        )
        Future.successful(CoinOperation.CO_ChannelPayment(requestCid))
      }
    )(
      user,
      _ => Empty(),
    )
  }

  override def rejectOnChannelPaymentRequest(
      request: v0.RejectOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
    }

  override def withdrawOnChannelPaymentRequest(
      request: v0.WithdrawOnChannelPaymentRequestRequest
  ): Future[Empty] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
      .exerciseCoinRules_TryTransfer(
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
      withAuth(request.getWalletCtx) { user =>
        for {
          userStore <- getUserStore(user)
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
  }

  private def getUserInstallContract(
      userWalletStore: EndUserWalletStore,
      userParty: PartyId,
  ): Future[Contract[walletCodegen.WalletAppInstall]] = for {
    installO <- userWalletStore.lookupInstall()
    install = installO match {
      case QueryResult(_, None) =>
        throw new StatusRuntimeException(
          Status.NOT_FOUND.withDescription(s"WalletAppInstall contract of user $userParty")
        )
      case QueryResult(_, Some(install)) => install
    }
  } yield install

  /** Executes a wallet action by calling the `WalletAppInstall_ExecuteBatch` choice on the WalletAppInstall
    * contract of the end user specified in the given WalletContext.
    *
    * The choice is always executed with the wallet service party as the submitter, and the
    * wallet user party as a readAs party.
    *
    * Additionally, the validator service party is also a readAs party (workaround for lack
    * of explicit disclosure for CoinRules).
    *
    * Note: curried syntax helps with type inference
    */
  private def exerciseWalletAction[Response](
      constructCoinOperation: TraceContext => (
          Primitive.ContractId[walletCodegen.WalletAppInstall],
          EndUserWalletStore,
          // TODO(#756): also require quantity to reject commands early?
      ) => Future[CoinOperation]
  )(
      user: String,
      // TODO(#756): possibly adjust the return type here depending on the caller
      processResponse: CoinOperationOutcome => Response,
  ): Future[Response] =
    withSpanFromGrpcContext("GrpcWalletService") { implicit traceContext => span =>
      for {
        userStore <- getUserStore(user)
        userTreasury <- getUserTreasury(user)
        userParty = userStore.key.endUserParty
        install <- getUserInstallContract(userStore, userParty)
        operation <- constructCoinOperation(traceContext)(install.contractId, userStore)
        res <- userTreasury
          .enqueueCoinOperation(operation)
          .map(processResponse)
      } yield res
    }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
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
