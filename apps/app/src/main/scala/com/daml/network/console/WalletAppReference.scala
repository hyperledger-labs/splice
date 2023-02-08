package com.daml.network.console

import akka.http.scaladsl.model.headers.{Authorization, OAuth2BearerToken}
import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.cc.coin as coinCodegen
import com.daml.network.codegen.java.cn.wallet.{
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOfferCodegen,
}
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.util.Contract
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient.{
  ListResponse,
  UserStatusData,
}
import com.daml.network.wallet.config.{WalletAppBackendConfig, WalletAppClientConfig}
import com.digitalasset.canton.DomainAlias
import com.digitalasset.canton.console.{BaseInspection, GrpcRemoteInstanceReference, Help}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.{DomainId, PartyId}

abstract class WalletAppReference(
    override val coinConsoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
) extends HttpCoinAppReference {

  override protected val instanceType = "Wallet user"

  protected def token: String

  private def headers = List(Authorization(OAuth2BearerToken(token)))

  @Help.Summary("List all coins associated with the configured user")
  @Help.Description(
    "Queries the configured remote participant for the Coins owned by the configured user. " +
      "Returns all found coins."
  )
  def list(): ListResponse = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListPositions(headers))
    }
  }

  @Help.Summary("Credits the requested amount of Canton coin to the wallet's user")
  @Help.Description(
    "This function will only be available in the devnet. It allows creating coins for testing purposes." +
      "Returns the contract ID of the created contract. "
  )
  def tap(amount: BigDecimal): coinCodegen.Coin.ContractId = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.Tap(amount, headers))
    }
  }

  @Help.Summary("Self-grant a featured app right")
  @Help.Description(
    "This function will only be available in the devnet. It allows an app provider to grant a featured app right to themselves without the SVC having to approve."
  )
  def selfGrantFeaturedAppRight(): coinCodegen.FeaturedAppRight.ContractId = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.SelfGrantFeaturedAppRight(headers))
    }
  }

  @Help.Summary("Retrieve an overall balance of coin holdings")
  @Help.Description(
    "Display a count across all coin holdings, consisting of: total unlocked coin balance, total locked coin balance, total holding fees accumulated. Balances are calculated after holding fees are applied."
  )
  def balance(): HttpWalletAppClient.Balance = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.GetBalance(headers))
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
      httpCommand(HttpWalletAppClient.ListAppPaymentRequests(headers))
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
      httpCommand(
        HttpWalletAppClient.AcceptAppPaymentRequest(requestId, headers)
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
      httpCommand(
        HttpWalletAppClient.RejectAppPaymentRequest(requestId, headers)
      )
    }
  }

  @Help.Summary("List all accepted app payments the user is a sender on")
  def listAcceptedAppPayments(): Seq[
    Contract[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListAcceptedAppPayments(headers))
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
      httpCommand(HttpWalletAppClient.ListSubscriptionRequests(headers))
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
      httpCommand(HttpWalletAppClient.ListSubscriptionInitialPayments(headers))
    }
  }

  @Help.Summary("List subscriptions of the configured user")
  @Help.Description(
    "Queries the configured remote participant for all Subscription contracts of the configured user. " +
      "Returns them, joining each of them with its current state contract."
  )
  def listSubscriptions(): Seq[HttpWalletAppClient.Subscription] = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListSubscriptions(headers))
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
      httpCommand(HttpWalletAppClient.AcceptSubscriptionRequest(requestId, headers))
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
      httpCommand(HttpWalletAppClient.RejectSubscriptionRequest(requestId, headers))
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
      httpCommand(HttpWalletAppClient.CancelSubscription(stateId, headers))
    }
  }

  @Help.Summary("Offer a transfer to another party")
  @Help.Description("Creates a transfer offer, to be accepted by the receiver")
  def createTransferOffer(
      receiver: PartyId,
      amount: BigDecimal,
      description: String,
      expiresAt: CantonTimestamp,
      idempotencyKey: String,
  ): transferOfferCodegen.TransferOffer.ContractId =
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient
          .CreateTransferOffer(receiver, amount, description, expiresAt, idempotencyKey, headers)
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
      httpCommand(HttpWalletAppClient.ListTransferOffers(headers))
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
      httpCommand(
        HttpWalletAppClient.AcceptTransferOffer(offerId, headers)
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
      httpCommand(HttpWalletAppClient.ListAcceptedTransferOffers(headers))
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
      httpCommand(
        HttpWalletAppClient.RejectTransferOffer(offerId, headers)
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
      httpCommand(
        HttpWalletAppClient.WithdrawTransferOffer(offerId, headers)
      )
    }
  }

  @Help.Summary("List app rewards")
  @Help.Description("List all open app rewards for the configured user")
  def listAppRewardCoupons()
      : Seq[Contract[coinCodegen.AppRewardCoupon.ContractId, coinCodegen.AppRewardCoupon]] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListAppRewardCoupons(headers))
    }

  @Help.Summary("List validator rewards")
  @Help.Description(
    "List all open validator rewards for the configured user based on the active ValidatorRights"
  )
  def listValidatorRewardCoupons(): Seq[
    Contract[coinCodegen.ValidatorRewardCoupon.ContractId, coinCodegen.ValidatorRewardCoupon]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListValidatorRewardCoupons(headers))
    }

  @Help.Summary("User status")
  @Help.Description("Get the user status")
  def userStatus(): UserStatusData =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.UserStatus(headers))
    }

  @Help.Summary("Cancel user's featured app rights")
  def cancelFeaturedAppRight(): Unit =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.CancelFeaturedAppRight(headers))
    }

  @Help.Summary("List the connected domains of the participant the app is running on")
  def listConnectedDomains(): Map[DomainAlias, DomainId] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListConnectedDomains(headers))
    }
}

/** Single local Wallet app reference. Defines the console commands that can be run against a local Wallet
  * app reference.
  */
class WalletAppBackendReference(
    val coinConsoleEnvironment: CoinConsoleEnvironment,
    val name: String,
) extends LocalCoinAppReference
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

/** Client (aka remote) reference to a wallet app in the style of CoinRemoteParticipantReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class WalletAppClientReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
    override val config: WalletAppClientConfig,
) extends WalletAppReference(consoleEnvironment, name)
    with GrpcRemoteInstanceReference
    with BaseInspection[ParticipantNode] {

  override def httpClientConfig = config.adminApi
  override def token: String = {
    AuthUtil.testToken(
      audience = AuthUtil.testAudience,
      user = config.ledgerApiUser,
    )
  }

  override protected val instanceType = "Wallet Client"
}
