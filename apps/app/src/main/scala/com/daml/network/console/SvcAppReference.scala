package com.daml.network.console

import com.daml.network.environment.CoinConsoleEnvironment
import com.daml.network.svc.admin.api.client.commands.SvcAppCommands
import com.daml.network.svc.config.LocalSvcAppConfig
import com.daml.network.validator.admin.api.client.commands.ValidatorAppCommands
import com.daml.network.validator.config.LocalValidatorAppConfig
import com.digitalasset.canton.admin.api.client.commands.GrpcAdminCommand
import com.digitalasset.canton.console.commands._
import com.digitalasset.canton.console.{
  BaseInspection,
  ConsoleCommandResult,
  ConsoleEnvironment,
  FeatureFlag,
  Help,
  InstanceReference,
  LedgerApiCommandRunner,
  LocalInstanceReference,
}
import com.digitalasset.canton.environment.CantonNodeBootstrap
import com.digitalasset.canton.health.admin.data.ParticipantStatus
import com.digitalasset.canton.logging.NamedLoggerFactory
import com.digitalasset.canton.participant.ParticipantNode
import com.digitalasset.canton.topology.ParticipantId

/** Copy of Canton ParticipantReference */
abstract class SvcAppReference(
    override val consoleEnvironment: ConsoleEnvironment,
    val name: String,
) extends InstanceReference
    with ParticipantAdministration
    with LedgerApiAdministration
    with LedgerApiCommandRunner {

  override type InstanceId = ParticipantId

  override protected val instanceType = "SVC"

  override protected val loggerFactory: NamedLoggerFactory =
    consoleEnvironment.environment.loggerFactory.append("SVC", name)

  override type Status = ParticipantStatus

  // TODO(Arne): remove/cleanup all the uninteresting console commands.
  @Help.Summary("Health and diagnostic related commands")
  @Help.Group("Health")
  override def health =
    new ParticipantHealthAdministration(this, consoleEnvironment, loggerFactory)

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
  def testing: ParticipantTestingGroup

  @Help.Summary(
    "Commands to pruning the archive of the ledger",
    FeatureFlag.Preview,
  )
  @Help.Group("Ledger Pruning")
  def pruning: ParticipantPruningAdministrationGroup

  @Help.Summary("Inspect and manage parties")
  @Help.Group("Parties")
  override def parties: ParticipantPartiesAdministrationGroup

  def config: LocalSvcAppConfig
}

/** Single local SVC app reference. Defines the console commands that can be run against a local SVC
  * app reference.
  */
class LocalSvcAppReference(
    override val consoleEnvironment: CoinConsoleEnvironment,
    name: String,
) extends SvcAppReference(consoleEnvironment, name)
    with LocalInstanceReference
    with BaseInspection[ParticipantNode] {

  protected val nodes = consoleEnvironment.environment.svcs
  @Help.Summary("Return participant config")
  def config: LocalSvcAppConfig =
    consoleEnvironment.environment.config.svcsByString(name)

  def initialize(): Unit = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.Initialize())
    }
  }

  def openNextRound(): Unit = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.OpenNextRound())
    }
  }

  def acceptValidators(): Unit = {
    consoleEnvironment.run {
      adminCommand(SvcAppCommands.AcceptValidators())
    }
  }

  /** Remote participant this SVC app is configured to interact with. */
  val remoteParticipant =
    new SvcAppRemoteParticipantReference(
      consoleEnvironment,
      s"remote participant for `$name``",
      name,
    )

  private lazy val pruning_ =
    new LocalParticipantPruningAdministrationGroup(
      this,
      consoleEnvironment,
      loggerFactory,
    )
  @Help.Summary(
    "Commands to truncate the archive of the ledger",
    FeatureFlag.Preview,
  )
  @Help.Group("Ledger Pruning")
  def pruning: LocalParticipantPruningAdministrationGroup = pruning_

  override def testing: ParticipantTestingGroup = ???

  @Help.Summary("Inspect and manage parties")
  @Help.Group("Parties")
  override def parties =
    partiesGroup

  // TODO(Arne): slightly adapted this.
  // above command needs to be def such that `Help` works.
  lazy private val partiesGroup =
    new ParticipantPartiesAdministrationGroup(this.id, this, consoleEnvironment)

  /** secret, not publicly documented way to get the admin token */
  def adminToken: Option[String] = underlying.map(_.adminToken.secret)

  override def runningNode: Option[CantonNodeBootstrap[ParticipantNode]] =
    consoleEnvironment.environment.participants.getRunning(name)

  // TODO(Arne): validators don't expose a ledger api, however, we maybe still want similar methods
  // to run against a participant's ledger API.
  override def ledgerApiCommand[Result](
      command: GrpcAdminCommand[_, _, Result]
  ): ConsoleCommandResult[Result] = ???

}
