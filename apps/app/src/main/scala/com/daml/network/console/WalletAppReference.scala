// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.console

import com.daml.network.auth.AuthUtil
import com.daml.network.codegen.java.splice.amulet as amuletCodegen
import com.daml.network.codegen.java.splice.validatorlicense as validatorLicenseCodegen
import com.daml.network.codegen.java.splice.wallet.{
  buytrafficrequest as trafficRequestCodegen,
  payment as walletCodegen,
  subscriptions as subsCodegen,
  transferoffer as transferOfferCodegen,
}
import com.daml.network.environment.SpliceConsoleEnvironment
import com.daml.network.http.v0.definitions.{
  GetBuyTrafficRequestStatusResponse,
  GetTransferOfferStatusResponse,
}
import com.daml.network.util.{Contract, ContractWithState}
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient
import com.daml.network.wallet.admin.api.client.commands.HttpWalletAppClient.{
  ListResponse,
  UserStatusData,
}
import com.daml.network.wallet.config.WalletAppClientConfig
import com.daml.network.wallet.store.TxLogEntry
import com.digitalasset.canton.console.Help
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.topology.{DomainId, PartyId}

abstract class WalletAppReference(
    override val spliceConsoleEnvironment: SpliceConsoleEnvironment,
    override val name: String,
) extends HttpAppReference {

  override def basePath = "/api/validator"
  override protected val instanceType = "Wallet user"

  @Help.Summary("List all amulets associated with the configured user")
  @Help.Description(
    "Queries the configured remote participant for the Amulets owned by the configured user. " +
      "Returns all found amulets."
  )
  def list(): ListResponse = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListPositions)
    }
  }

  @Help.Summary(
    "Credits an amount of Amulet corresponding to the requested USD amount to the wallet's user"
  )
  @Help.Description(
    "This function will only be available in the devnet. It allows creating amulets for testing purposes." +
      "Returns the contract ID of the created contract. "
  )
  def tap(usdAmount: BigDecimal): amuletCodegen.Amulet.ContractId = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.Tap(usdAmount))
    }
  }

  @Help.Summary("Self-grant a featured app right")
  @Help.Description(
    "This function will only be available in the devnet. It allows an app provider to grant a featured app right to themselves without the DSO having to approve."
  )
  def selfGrantFeaturedAppRight(): amuletCodegen.FeaturedAppRight.ContractId = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.SelfGrantFeaturedAppRight)
    }
  }

  @Help.Summary("Retrieve an overall balance of amulet holdings")
  @Help.Description(
    "Display a count across all amulet holdings, consisting of: total unlocked amulet balance, total locked amulet balance, total holding fees accumulated. Balances are calculated after holding fees are applied."
  )
  def balance(): HttpWalletAppClient.Balance = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.GetBalance)
    }
  }

  @Help.Summary("List all payment requests of the configured user")
  @Help.Description(
    "Queries the configured remote participant for the PaymentRequests of the configured user. " +
      "Returns all found payment requests."
  )
  def listAppPaymentRequests(): Seq[
    ContractWithState[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest]
  ] = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListAppPaymentRequests)
    }
  }

  @Help.Summary("Get a payment request")
  @Help.Description(
    "Queries the configured remote participant for the PaymentRequest with the passed contractId. " +
      "Returns the contract of the payment request."
  )
  def getAppPaymentRequest(
      contractId: walletCodegen.AppPaymentRequest.ContractId
  ): Contract[walletCodegen.AppPaymentRequest.ContractId, walletCodegen.AppPaymentRequest] = {
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient.GetAppPaymentRequest(contractId)
      )
    }
  }

  @Help.Summary("Accept a payment request")
  @Help.Description(
    "Accept a payment request and deliver the amulet to be locked into the accepted payment." +
      " Returns the contract ID of the accepted payment."
  )
  def acceptAppPaymentRequest(
      requestId: walletCodegen.AppPaymentRequest.ContractId
  ): walletCodegen.AcceptedAppPayment.ContractId = {
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient.AcceptAppPaymentRequest(requestId)
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
        HttpWalletAppClient.RejectAppPaymentRequest(requestId)
      )
    }
  }

  @Help.Summary("List all accepted app payments the user is a sender on")
  def listAcceptedAppPayments(): Seq[
    ContractWithState[walletCodegen.AcceptedAppPayment.ContractId, walletCodegen.AcceptedAppPayment]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListAcceptedAppPayments)
    }

  @Help.Summary("Get a subscription request")
  @Help.Description(
    "Queries the configured remote participant for the SubscriptionRequest with the passed contractId. " +
      "Returns the contract of the subscription requests."
  )
  def getSubscriptionRequest(
      contractId: subsCodegen.SubscriptionRequest.ContractId
  ): Contract[subsCodegen.SubscriptionRequest.ContractId, subsCodegen.SubscriptionRequest] = {
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient.GetSubscriptionRequest(contractId)
      )
    }
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
      httpCommand(HttpWalletAppClient.ListSubscriptionRequests)
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
      httpCommand(HttpWalletAppClient.ListSubscriptionInitialPayments)
    }
  }

  @Help.Summary("List subscriptions of the configured user")
  @Help.Description(
    "Queries the configured remote participant for all Subscription contracts of the configured user. " +
      "Returns them, joining each of them with its current state contract."
  )
  def listSubscriptions(): Seq[HttpWalletAppClient.Subscription] = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListSubscriptions)
    }
  }

  @Help.Summary("Accept a subscription request")
  @Help.Description(
    "Accept a payment request and deliver the amulet to be locked into the initial subscription payment." +
      " Returns the contract ID of the initial subscription payment."
  )
  def acceptSubscriptionRequest(
      requestId: subsCodegen.SubscriptionRequest.ContractId
  ): subsCodegen.SubscriptionInitialPayment.ContractId = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.AcceptSubscriptionRequest(requestId))
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
      httpCommand(HttpWalletAppClient.RejectSubscriptionRequest(requestId))
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
      httpCommand(HttpWalletAppClient.CancelSubscription(stateId))
    }
  }

  @Help.Summary("Offer a transfer to another party")
  @Help.Description("Creates a transfer offer, to be accepted by the receiver")
  def createTransferOffer(
      receiver: PartyId,
      amount: BigDecimal,
      description: String,
      expiresAt: CantonTimestamp,
      trackingId: String,
  ): transferOfferCodegen.TransferOffer.ContractId =
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient
          .CreateTransferOffer(receiver, amount, description, expiresAt, trackingId)
      )
    }

  @Help.Summary("Get transfer offer status")
  @Help.Description("Returns the status of a transfer offer.")
  def getTransferOfferStatus(trackingId: String): GetTransferOfferStatusResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient.GetTransferOfferStatus(trackingId)
      )
    }
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
      httpCommand(HttpWalletAppClient.ListTransferOffers)
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
        HttpWalletAppClient.AcceptTransferOffer(offerId)
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
      httpCommand(HttpWalletAppClient.ListAcceptedTransferOffers)
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
        HttpWalletAppClient.RejectTransferOffer(offerId)
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
        HttpWalletAppClient.WithdrawTransferOffer(offerId)
      )
    }
  }

  @Help.Summary("Make a request to buy domain traffic")
  @Help.Description(
    "Creates a request to buy extra traffic on the specified domain for the specified validator's participant"
  )
  def createBuyTrafficRequest(
      receivingValidator: PartyId,
      domainId: DomainId,
      trafficAmount: Long,
      trackingId: String,
      expiresAt: CantonTimestamp,
  ): trafficRequestCodegen.BuyTrafficRequest.ContractId =
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient
          .CreateBuyTrafficRequest(
            receivingValidator,
            domainId,
            trafficAmount,
            expiresAt,
            trackingId,
          )
      )
    }

  @Help.Summary("Get traffic request status")
  @Help.Description("Returns the status of a buy traffic request.")
  def getTrafficRequestStatus(trackingId: String): GetBuyTrafficRequestStatusResponse = {
    consoleEnvironment.run {
      httpCommand(
        HttpWalletAppClient.GetTrafficRequestStatus(trackingId)
      )
    }
  }

  @Help.Summary("List app rewards")
  @Help.Description("List all open app rewards for the configured user")
  def listAppRewardCoupons()
      : Seq[Contract[amuletCodegen.AppRewardCoupon.ContractId, amuletCodegen.AppRewardCoupon]] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListAppRewardCoupons)
    }

  @Help.Summary("List validator rewards")
  @Help.Description(
    "List all open validator rewards for the configured user based on the active ValidatorRights"
  )
  def listValidatorRewardCoupons(): Seq[
    Contract[amuletCodegen.ValidatorRewardCoupon.ContractId, amuletCodegen.ValidatorRewardCoupon]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListValidatorRewardCoupons)
    }

  @Help.Summary("List validator faucet rewards")
  @Help.Description(
    "List all open validator faucets rewards for the configured user based on the active ValidatorRights"
  )
  def listValidatorFaucetCoupons(): Seq[
    Contract[
      validatorLicenseCodegen.ValidatorFaucetCoupon.ContractId,
      validatorLicenseCodegen.ValidatorFaucetCoupon,
    ]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListValidatorFaucetCoupons)
    }

  @Help.Summary("List validator liveness activity records")
  @Help.Description(
    "List all open validator liveness activity records for the configured user based on the active ValidatorRights"
  )
  def listValidatorLivenessActivityRecords(): Seq[
    Contract[
      validatorLicenseCodegen.ValidatorLivenessActivityRecord.ContractId,
      validatorLicenseCodegen.ValidatorLivenessActivityRecord,
    ]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListValidatorLivenessActivityRecords)
    }

  @Help.Summary("List SV reward coupons")
  @Help.Description(
    "List all open SV Reward coupons issued the authenticated SV user"
  )
  def listSvRewardCoupons(): Seq[
    Contract[
      amuletCodegen.SvRewardCoupon.ContractId,
      amuletCodegen.SvRewardCoupon,
    ]
  ] =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListSvRewardCoupons)
    }

  @Help.Summary("User status")
  @Help.Description("Get the user status")
  def userStatus(): UserStatusData =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.UserStatus)
    }

  @Help.Summary("Cancel user's featured app rights")
  def cancelFeaturedAppRight(): Unit =
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.CancelFeaturedAppRight)
    }

  @Help.Summary("List transaction history")
  @Help.Description(
    "Shows items from the transaction history."
  )
  def listTransactions(
      beginAfterId: Option[String],
      pageSize: Int,
  ): Seq[TxLogEntry.TransactionHistoryTxLogEntry] = {
    consoleEnvironment.run {
      httpCommand(HttpWalletAppClient.ListTransactions(beginAfterId, pageSize))
    }
  }
}

/** Client (aka remote) reference to a wallet app in the style of ParticipantClientReference, i.e.,
  * it accepts the config as an argument rather than reading it from the global map.
  */
final class WalletAppClientReference(
    override val consoleEnvironment: SpliceConsoleEnvironment,
    name: String,
    val config: WalletAppClientConfig,
) extends WalletAppReference(consoleEnvironment, name) {

  override def httpClientConfig = config.adminApi
  override def token: Option[String] = {
    Some(
      AuthUtil.testToken(
        audience = AuthUtil.testAudience,
        user = config.ledgerApiUser,
        secret = AuthUtil.testSecret,
      )
    )
  }

  override protected val instanceType = "Wallet Client"
}
