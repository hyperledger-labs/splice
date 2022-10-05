package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.commands.{
  LedgerApiAdministration,
  ParticipantAdministration,
  HealthAdministration,
  ParticipantPartiesAdministrationGroup,
  ParticipantPruningAdministrationGroup,
  ParticipantTestingGroup,
  TopologyAdministrationGroup,
}
import com.digitalasset.canton.console.{
  ConsoleCommandResult,
  ConsoleEnvironment,
  ConsoleMacros,
  FeatureFlag,
  Help,
  InstanceReference,
  LedgerApiCommandRunner,
  RemoteParticipantReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.health.admin.data.SimpleStatus
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.participant.config.RemoteParticipantConfig
import com.digitalasset.canton.topology.ParticipantId

/** Copy of Canton ParticipantReference */
abstract class CoinAppReference(
    override val consoleEnvironment: ConsoleEnvironment,
    val name: String,
) extends InstanceReference
    with ParticipantAdministration
    with LedgerApiAdministration
    with LedgerApiCommandRunner {

  override type InstanceId = ParticipantId

  override protected val loggerFactory: NamedLoggerFactory =
    consoleEnvironment.environment.loggerFactory.append("Wallet", name)

  override type Status = SimpleStatus

  // TODO(i736): remove/cleanup all the uninteresting console commands copied from Canton.
  @Help.Summary("Health and diagnostic related commands")
  @Help.Group("Health")
  override def health =
    new HealthAdministration[SimpleStatus](this, consoleEnvironment, SimpleStatus.fromProtoV0)

  @Help.Summary(
    "Yields the globally unique id of this participant. " +
      "Throws an exception, if the id has not yet been allocated (e.g., the participant has not yet been started)."
  )
  override def id: ParticipantId = topology.idHelper(name, ParticipantId(_))

  private lazy val topology_ =
    new TopologyAdministrationGroup(
      this,
      health.status.successOption.map(_.topologyQueue),
      consoleEnvironment,
      loggerFactory,
    )
  @Help.Summary("Topology management related commands")
  @Help.Group("Topology")
  @Help.Description(
    "This group contains access to the full set of topology management commands."
  )
  def topology: TopologyAdministrationGroup = topology_

  @Help.Summary(
    "Commands used for development and testing",
    FeatureFlag.Testing,
  )
  @Help.Group("Testing")
  def testing: ParticipantTestingGroup = ???

  @Help.Summary(
    "Commands to pruning the archive of the ledger",
    FeatureFlag.Preview,
  )
  @Help.Group("Ledger Pruning")
  def pruning: ParticipantPruningAdministrationGroup = ???

  @Help.Summary("Inspect and manage parties")
  @Help.Group("Parties")
  override def parties: ParticipantPartiesAdministrationGroup = partiesGroup

  @Help.Summary("Wait until initialization has completed")
  def waitForInitialization()(implicit env: ConsoleEnvironment): Unit =
    ConsoleMacros.utils.retry_until_true(health.status.successOption.map(_.active).getOrElse(false))

  // TODO(i736): slightly adapted compared to Canton.
  // above command needs to be def such that `Help` works.
  lazy private val partiesGroup =
    new ParticipantPartiesAdministrationGroup(this.id, this, consoleEnvironment)

  override def ledgerApiCommand[Result](
      command: GrpcAdminCommand[_, _, Result]
  ): ConsoleCommandResult[Result] = ???

  def runningNode: Option[CantonNodeBootstrap[ParticipantNode]] =
    consoleEnvironment.environment.participants.getRunning(name)
}

/** Subclass of RemoteParticipant that takes the config as an argument
  * instead of relying on remoteParticipantsByName.
  */
class CoinRemoteParticipantReference(
    consoleEnvironment: CoinConsoleEnvironment,
    override val name: String,
    appName: String,
    override val config: RemoteParticipantConfig,
) extends RemoteParticipantReference(consoleEnvironment, name) {}
