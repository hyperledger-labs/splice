package com.daml.network.console
import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.splitwise.config.LocalSplitwiseAppConfig
import com.digitalasset.canton.console.{BaseInspection, Help, LocalInstanceReference}
import com.digitalasset.canton.participant.ParticipantNode

/** Single local Splitwise app reference. Defines the console commands that can be run against a local Splitwise
  * app reference.
  */
class LocalSplitwiseAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends CoinAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  override protected val instanceType = "Splitwise"

  protected val nodes = consoleEnvironment.environment.splitwises
  @Help.Summary("Return splitwise app config")
  def config: LocalSplitwiseAppConfig =
    consoleEnvironment.environment.config.splitwisesByString(name)

  /** Remote participant this Splitwise app is configured to interact with. */
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
