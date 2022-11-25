package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.auth.{AuthUtil, JwtCallCredential}
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
  paymentchannel as channelCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOfferCodegen,
}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.util.JavaContract as Contract
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient
import com.daml.network.wallet.admin.api.client.commands.GrpcWalletAppClient.{
  ListResponse,
  UserStatusData,
}
import com.daml.network.wallet.config.{LocalWalletAppConfig, RemoteWalletAppConfig}
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

import java.util.concurrent.atomic.AtomicReference

abstract class WalletAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference {

  protected def token: String
  private def callCredentials = Some(new JwtCallCredential(token))

  @Help.Summary("List all coins associated with the configured user")
  @Help.Description(
    "Queries the configured remote participant for the Coins owned by the configured user. " +
      "Returns all found coins."
  )
  def list(): ListResponse = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.List(), callCredentials)
    }
  }

  @Help.Summary("Credits the requested quantity of Canton coin to the wallet's user")
  @Help.Description(
    "This function will only be available in the testnet. It allows creating coins for testing purposes." +
      "Returns the contract ID of the created contract. "
  )
  def tap(quantity: BigDecimal): coinCodegen.Coin.ContractId = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.Tap(quantity), callCredentials)
    }
  }

  @Help.Summary("Retrieve an overall balance of coin holdings")
  @Help.Description(
    "Display a count across all coin holdings, consisting of: total unlocked coin balance, total locked coin balance, total holding fees accumulated. Balances are calculated after holding fees are applied."
  )
  def balance(): GrpcWalletAppClient.Balance = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.GetBalance(), callCredentials)
    }
  }

  @Help.Summary("List all payment requests of the configured user")
  @Help.Description(
    "Queries the configured remote participant for the PaymentRequests of the configured user. " +
      "Returns all found payment requests."
  )
  def listAppPaymentRequests(): Seq[
    Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
  ] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListAppPaymentRequests(), callCredentials)
    }
  }

  @Help.Summary("Accept a payment request")
  @Help.Description(
    "Accept a payment request and deliver the coin to be locked into the accepted payment." +
      " Returns the contract ID of the accepted payment."
  )
  def acceptAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ): walletCodegen.AcceptedAppPayment.ContractId = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.AcceptAppPaymentRequest(requestId),
        callCredentials,
      )
    }
  }

  @Help.Summary("Reject a payment request")
  @Help.Description(
    "Reject a payment request."
  )
  def rejectAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.RejectAppPaymentRequest(requestId),
        callCredentials,
      )
    }
  }

  @Help.Summary("List all accepted app payments the user is a sender on")
  def listAcceptedAppPayments(): Seq[
    Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment]
  ] =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListAcceptedAppPayments(), callCredentials)
    }

  @Help.Summary("List all subscription requests of the configured user")
  @Help.Description(
    "Queries the configured remote participant for the SubscriptionRequests of the configured user. " +
      "Returns all found subscription requests."
  )
  def listSubscriptionRequests(): Seq[
    Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest]
  ] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListSubscriptionRequests(), callCredentials)
    }
  }

  @Help.Summary("List initial subscription payments of the configured user")
  @Help.Description(
    "Queries the configured remote participant for the SubscriptionInitialPayments of the configured user. " +
      "Returns all found payments."
  )
  def listSubscriptionInitialPayments(): Seq[Contract[
    subsCodegen.SubscriptionInitialPayment.ContractId,
    subsCodegen.SubscriptionInitialPayment,
  ]] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListSubscriptionInitialPayments(), callCredentials)
    }
  }

  @Help.Summary("List subscriptions of the configured user")
  @Help.Description(
    "Queries the configured remote participant for all Subscription contracts of the configured user. " +
      "Returns them, joining each of them with its current state contract."
  )
  def listSubscriptions(): Seq[GrpcWalletAppClient.Subscription] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListSubscriptions(), callCredentials)
    }
  }

  @Help.Summary("Accept a subscription request")
  @Help.Description(
    "Accept a payment request and deliver the coin to be locked into the initial subscription payment." +
      " Returns the contract ID of the initial subscription payment."
  )
  def acceptSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ): subsCodegen.SubscriptionInitialPayment.ContractId = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.AcceptSubscriptionRequest(requestId), callCredentials)
    }
  }

  @Help.Summary("Reject a subscription request")
  @Help.Description(
    "Reject a subscription request."
  )
  def rejectSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.RejectSubscriptionRequest(requestId), callCredentials)
    }
  }

  @Help.Summary("Cancel a subscription")
  @Help.Description(
    "Cancels a subscription that is in idle state."
  )
  def cancelSubscription(
      stateId: subsCodegen.SubscriptionIdleState.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.CancelSubscription(stateId), callCredentials)
    }
  }

  @Help.Summary("Propose the creation of a payment channel")
  @Help.Description(
    "Propose the creation of a uni-directional payment channel with a specific receiver." +
      " Returns the contract-id of the created proposal."
  )
  def proposePaymentChannel(
      receiver: PartyId,
      replacesChannelId: Option[channelCodegen.PaymentChannel.ContractId] = None,
      allowRequests: Boolean = true,
      allowOffers: Boolean = true,
      allowDirectTransfers: Boolean = true,
      senderTransferFeeRatio: Double = 1.0,
  ): channelCodegen.PaymentChannelProposal.ContractId = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.ProposePaymentChannel(
          receiver,
          replacesChannelId,
          allowRequests,
          allowOffers,
          allowDirectTransfers,
          senderTransferFeeRatio,
        ),
        callCredentials,
      )
    }
  }

  @Help.Summary("List active payment channel proposals")
  @Help.Description(
    "Shows both incoming and outgoing payment channel proposals."
  )
  def listPaymentChannelProposals(): Seq[
    Contract[
      channelCodegen.PaymentChannelProposal.ContractId,
      channelCodegen.PaymentChannelProposal,
    ]
  ] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListPaymentChannelProposals(), callCredentials)
    }
  }

  @Help.Summary("List payment channels")
  @Help.Description(
    "Shows payment channels where the user is either the sender or the receiver"
  )
  def listPaymentChannels()
      : Seq[Contract[channelCodegen.PaymentChannel.ContractId, channelCodegen.PaymentChannel]] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListPaymentChannels(), callCredentials)
    }
  }

  @Help.Summary("Accept a payment channel proposal.")
  @Help.Description(
    "Accept a specific payment channel proposal."
  )
  def acceptPaymentChannelProposal(
      proposalId: channelCodegen.PaymentChannelProposal.ContractId
  ): channelCodegen.PaymentChannel.ContractId = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.AcceptPaymentChannelProposal(proposalId),
        callCredentials,
      )
    }
  }

  @Help.Summary("Cancel an existing payment channel, by providing the receiver.")
  @Help.Description(
    "Cancel an existing payment channel associated with a receiver." +
      "Prevents subsequent use of that channel. The sender is assumed to be the wallet user."
  )
  def cancelPaymentChannelByReceiver(receiverPartyId: PartyId): Unit =
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.CancelPaymentChannelByReceiver(receiverPartyId),
        callCredentials,
      )
    }

  @Help.Summary("Cancel an existing payment channel, by providing the sender.")
  @Help.Description(
    "Cancel an existing payment channel associated with a sender." +
      "Prevents subsequent use of that channel. The receiver is assumed to be the wallet user."
  )
  def cancelPaymentChannelBySender(senderPartyId: PartyId): Unit =
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.CancelPaymentChannelBySender(senderPartyId),
        callCredentials,
      )
    }

  @Help.Summary("Execute a direct transfer over a payment channel")
  @Help.Description(
    "Assumes that the payment channel for the given receiver already exists."
  )
  def executeDirectTransfer(
      receiver: PartyId,
      quantity: BigDecimal,
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.ExecuteDirectTransfer(receiver, quantity),
        callCredentials,
      )
    }
  }

  @Help.Summary("Request a payment through a payment channel")
  @Help.Description(
    "Assumes that the payment channel for the given sender already exists."
  )
  def createOnChannelPaymentRequest(
      sender: PartyId,
      quantity: BigDecimal,
      description: String,
  ): channelCodegen.OnChannelPaymentRequest.ContractId = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.CreateOnChannelPaymentRequest(
          sender,
          quantity,
          description,
        ),
        callCredentials,
      )
    }
  }

  @Help.Summary("List all on-channel payment requests of the configured user")
  @Help.Description(
    "Shows all incoming and outgoing payment requests over payment channels."
  )
  def listOnChannelPaymentRequests(): Seq[Contract[
    channelCodegen.OnChannelPaymentRequest.ContractId,
    channelCodegen.OnChannelPaymentRequest,
  ]] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListOnChannelPaymentRequests(), callCredentials)
    }
  }

  @Help.Summary("Accept a request for payment through a payment channel")
  @Help.Description(
    "Accepts the request using the given coin."
  )
  def acceptOnChannelPaymentRequest(
      requestId: channelCodegen.OnChannelPaymentRequest.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.AcceptOnChannelPaymentRequest(requestId),
        callCredentials,
      )
    }
  }

  @Help.Summary("Reject a request for payment through a payment channel")
  @Help.Description(
    "Rejects the request."
  )
  def rejectOnChannelPaymentRequest(
      requestId: channelCodegen.OnChannelPaymentRequest.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.RejectOnChannelPaymentRequest(requestId),
        callCredentials,
      )
    }
  }

  @Help.Summary("Withdraw a request for payment through a payment channel")
  @Help.Description(
    "Withdraws the request."
  )
  def withdrawOnChannelPaymentRequest(
      requestId: channelCodegen.OnChannelPaymentRequest.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.WithdrawOnChannelPaymentRequest(requestId),
        callCredentials,
      )
    }
  }

  @Help.Summary("Offer a transfer to another party")
  @Help.Description("Creates a transfer offer, to be accepted by the receiver")
  def createTransferOffer(
      receiver: PartyId,
      quantity: BigDecimal,
      description: String,
      expiresAt: Primitive.Timestamp,
  ): transferOfferCodegen.TransferOffer.ContractId =
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.CreateTransferOffer(receiver, quantity, description, expiresAt),
        callCredentials,
      )
    }

  @Help.Summary("List active transfer offers")
  @Help.Description(
    "Shows both incoming and outgoing transfer offers."
  )
  def listTransferOffers(): Seq[
    Contract[
      transferOfferCodegen.TransferOffer.ContractId,
      transferOfferCodegen.TransferOffer,
    ]
  ] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListTransferOffers(), callCredentials)
    }
  }

  @Help.Summary("Accept a transfer offer.")
  @Help.Description(
    "Accept a specific offer for a direct transfer."
  )
  def acceptTransferOffer(
      offerId: transferOfferCodegen.TransferOffer.ContractId
  ): transferOfferCodegen.AcceptedTransferOffer.ContractId = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.AcceptTransferOffer(offerId),
        callCredentials,
      )
    }
  }

  @Help.Summary("List accepted transfer offers")
  @Help.Description(
    "Shows accepted transfer offers where the user is either a receiver or a sender."
  )
  def listAcceptedTransferOffers(): Seq[
    Contract[
      transferOfferCodegen.AcceptedTransferOffer.ContractId,
      transferOfferCodegen.AcceptedTransferOffer,
    ]
  ] = {
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListAcceptedTransferOffers(), callCredentials)
    }
  }

  @Help.Summary("Reject a transfer offer.")
  @Help.Description(
    "Reject a specific offer for a direct transfer (as the receiver)."
  )
  def rejectTransferOffer(
      offerId: transferOfferCodegen.TransferOffer.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.RejectTransferOffer(offerId),
        callCredentials,
      )
    }
  }

  @Help.Summary("Withdraw a transfer offer.")
  @Help.Description(
    "Withdraw a specific offer for a direct transfer (as the sender)."
  )
  def withdrawTransferOffer(
      offerId: transferOfferCodegen.TransferOffer.ContractId
  ): Unit = {
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient.WithdrawTransferOffer(offerId),
        callCredentials,
      )
    }
  }

  @Help.Summary("List app rewards")
  @Help.Description("List all open app rewards for the configured user")
  def listAppRewards(): Seq[Contract[coinCodegen.AppReward.ContractId, coinCodegen.AppReward]] =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListAppRewards(), callCredentials)
    }

  @Help.Summary("List validator rewards")
  @Help.Description(
    "List all open validator rewards for the configured user based on the active ValidatorRights"
  )
  def listValidatorRewards()
      : Seq[Contract[coinCodegen.ValidatorReward.ContractId, coinCodegen.ValidatorReward]] =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListValidatorRewards(), callCredentials)
    }

  @Help.Summary("Collect rewards")
  @Help.Description(
    "Merge all currently open app and validator rewards for the given round with an existing coin"
  )
  def collectRewards(
      round: Long
  ): Unit =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.CollectRewards(round), callCredentials)
    }

  @Help.Summary("User status")
  @Help.Description("Get the user status")
  def userStatus(): UserStatusData =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.UserStatus(), callCredentials)
    }
}

class RemoteWalletAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: RemoteWalletAppConfig,
) extends WalletAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Remote wallet"

  override def token: String = {
    AuthUtil.testTokenBearer(
      audience = AuthUtil.audience(config.adminApi.address, "wallet"),
      user = config.damlUser,
    )
  }
}

/** Single local Wallet app reference. Defines the console commands that can be run against a local Wallet
  * app reference.
  */
class LocalWalletAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends WalletAppReference(consoleEnvironment, name)
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Wallet"

  val tokenRef: AtomicReference[Option[String]] = new AtomicReference(None)

  override def token: String = {
    tokenRef.get match {
      case Some(t) => t
      case None =>
        throw new Exception("Token not defined! Set using \".setWalletContext\" command.")
    }
  }

  protected val nodes = consoleEnvironment.environment.wallets

  @Help.Summary("Return wallet app config")
  def config: LocalWalletAppConfig =
    consoleEnvironment.environment.config.walletsByString(name)

  @Help.Summary("Set wallet context")
  def setWalletContext(userId: String): Unit = {
    val token = AuthUtil.testTokenBearer(
      audience = AuthUtil.audience(config.adminApi.address, "wallet"),
      user = userId,
    )
    tokenRef.set(Some(token))
  }

  /** Remote participant this Wallet app is configured to interact with. */
  val remoteParticipant =
    new CoinRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
