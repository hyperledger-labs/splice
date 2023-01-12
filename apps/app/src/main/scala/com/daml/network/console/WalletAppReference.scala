package com.daml.network.console

import com.daml.network.auth.{AuthUtil, JwtCallCredential}
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
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
import com.daml.network.wallet.config.{WalletAppBackendConfig, WalletAppClientConfig}
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId

class WalletAppClientReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
    override val config: WalletAppClientConfig,
) extends CoinAppReference
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Wallet user"

  private def token: String = {
    AuthUtil.testToken(
      audience = AuthUtil.testAudience,
      user = config.damlUser,
    )
  }

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

  @Help.Summary("Offer a transfer to another party")
  @Help.Description("Creates a transfer offer, to be accepted by the receiver")
  def createTransferOffer(
      receiver: PartyId,
      quantity: BigDecimal,
      description: String,
      expiresAt: CantonTimestamp,
      idempotencyKey: String,
      senderFeeTransferRatio: BigDecimal = 1.0,
  ): transferOfferCodegen.TransferOffer.ContractId =
    consoleEnvironment.run {
      adminCommand(
        GrpcWalletAppClient
          .CreateTransferOffer(
            receiver,
            quantity,
            description,
            expiresAt,
            senderFeeTransferRatio,
            idempotencyKey,
          ),
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
  def listAppRewardCoupons()
      : Seq[Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]] =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListAppRewardCoupons(), callCredentials)
    }

  @Help.Summary("List validator rewards")
  @Help.Description(
    "List all open validator rewards for the configured user based on the active ValidatorRights"
  )
  def listValidatorRewardCoupons(): Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ] =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.ListValidatorRewardCoupons(), callCredentials)
    }

  @Help.Summary("User status")
  @Help.Description("Get the user status")
  def userStatus(): UserStatusData =
    consoleEnvironment.run {
      adminCommand(GrpcWalletAppClient.UserStatus(), callCredentials)
    }
}

/** Single local Wallet app reference. Defines the console commands that can be run against a local Wallet
  * app reference.
  */
class WalletAppBackendReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends CoinAppReference
    with LocalCoinAppReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Wallet"

  protected val nodes = coinConsoleEnvironment.environment.wallets

  @Help.Summary("Return wallet app backend config")
  def config: WalletAppBackendConfig =
    coinConsoleEnvironment.environment.config.walletBackendsByString(name)

  /** Remote participant this wallet app is configured to interact with. */
  lazy val remoteParticipant =
    new CoinRemoteParticipantReference(
      coinConsoleEnvironment,
      s"remote participant for `$name``",
      name,
      config.remoteParticipant.getRemoteParticipantConfig(),
    )

  /** Remote participant this wallet app is configured to interact with. Uses admin tokens to bypass auth. */
  lazy val remoteParticipantWithAdminToken =
    new CoinRemoteParticipantReference(
      coinConsoleEnvironment,
      s"remote participant for `$name`, with admin token",
      name,
      config.remoteParticipant.remoteParticipantConfigWithAdminToken,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
