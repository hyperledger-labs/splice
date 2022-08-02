package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.util.Contract
import com.daml.network.wallet.admin.api.client.commands.WalletAppCommands
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.{Coin => coinCodegen}
import com.digitalasset.network.CN.Wallet.{PaymentRequest => walletCodegen}

/** Single local Wallet app reference. Defines the console commands that can be run against a local Wallet
  * app reference.
  */
class LocalWalletAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  protected val nodes = consoleEnvironment.environment.wallets
  @Help.Summary("Return wallet app config")
  def config: LocalWalletAppConfig =
    consoleEnvironment.environment.config.walletsByString(name)

  @Help.Summary("List all coins associated with the configured user")
  @Help.Description(
    "Queries the configured remote participant for the Coins owned by the configured user. " +
      "Returns all found coins."
  )
  def list(): Seq[Contract[coinCodegen.Coin]] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.List())
    }
  }

  def initialize(svc: PartyId, validator: PartyId): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.Initialize(svc, validator))
    }
  }

  @Help.Summary("Credits the requested amount of Canton coin to the wallet's user")
  @Help.Description(
    "This function will only be available in the testnet. It allows creating coins for testing purposes." +
      "Returns the contract ID of the created contract. "
  )
  def tap(amount: String = "100"): Primitive.ContractId[coinCodegen.Coin] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.Tap(com.daml.lf.data.Numeric.assertFromString(amount)))
    }
  }

  @Help.Summary("List all payment requests of the configured user")
  @Help.Description(
    "Queries the configured remote participant for the PaymentRequests of the configured user. " +
      "Returns all found payment requests."
  )
  def listPaymentRequests(): Seq[Contract[walletCodegen.PaymentRequest]] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListPaymentRequests())
    }
  }

  @Help.Summary("Approve a payment request")
  @Help.Description(
    "Approve a payment request and deliver the coin to be locked into the approved payment." +
      "Returns the contract ID of the approved payment."
  )
  def approvePaymentRequest(
      requestId: Primitive.ContractId[walletCodegen.PaymentRequest],
      coinId: Primitive.ContractId[coinCodegen.Coin],
  ): Primitive.ContractId[walletCodegen.ApprovedPayment] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ApprovePaymentRequest(requestId, coinId))
    }
  }

  @Help.Summary("Reject a payment request")
  @Help.Description(
    "Reject a payment request."
  )
  def rejectPaymentRequest(requestId: Primitive.ContractId[walletCodegen.PaymentRequest]): Unit = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.RejectPaymentRequest(requestId))
    }
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
