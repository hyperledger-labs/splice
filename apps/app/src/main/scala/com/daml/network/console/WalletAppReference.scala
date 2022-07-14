package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.wallet.admin.api.client.commands.WalletCommands
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
  def list(): String = {
    consoleEnvironment.run {
      adminCommand(WalletCommands.List())
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
