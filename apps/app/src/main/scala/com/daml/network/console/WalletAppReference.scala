package com.daml.network.console

import com.daml.ledger.client.binding.Primitive
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.wallet.domain.{CantonCoin, PaymentRequest}
import com.daml.network.wallet.admin.api.client.commands.WalletAppCommands
import com.daml.network.wallet.config.LocalWalletAppConfig
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  Help,
  LocalInstanceReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.network.CC.Coin.Coin

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
  @Help.Summary("Return participant config")
  def config: LocalWalletAppConfig =
    consoleEnvironment.environment.config.walletsByString(name)

  @Help.Summary("List all coins associated with the configured user")
  @Help.Description(
    "Queries the configured remote participant for the Coins owned by the configured user. " +
      "Returns all found coins."
  )
  def list(): Seq[CantonCoin] = {
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
  def tap(amount: String = "100"): Primitive.ContractId[Coin] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.Tap(com.daml.lf.data.Numeric.assertFromString(amount)))
    }
  }

  @Help.Summary("List all payment requests of the configured user")
  @Help.Description(
    "Queries the configured remote participant for the PaymentRequests of the configured user. " +
      "Returns all found payment requests."
  )
  def listPaymentRequests(): Seq[PaymentRequest] = {
    consoleEnvironment.run {
      adminCommand(WalletAppCommands.ListPaymentRequests())
    }
  }

  /** Remote participant this Wallet app is configured to interact with. */
  val remoteParticipant =
    new WalletAppRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
    )

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)
}
