package com.daml.network.environment

import com.daml.network.console.LocalValidatorReference
import com.digitalasset.canton.admin.api.client.data.CommunityCantonStatus
import com.digitalasset.canton.console.{
  CantonHealthAdministration,
  CommunityCantonHealthAdministration,
  CommunityLocalDomainReference,
  CommunityRemoteDomainReference,
  ConsoleEnvironment,
  ConsoleGrpcAdminCommandRunner,
  ConsoleOutput,
  DomainReference,
  FeatureFlag,
  Help,
  LocalDomainReference,
  LocalInstanceReference,
  LocalParticipantReference,
  NodeReferences,
  StandardConsoleOutput,
}

class CoinConsoleEnvironment(
    val environment: CoinEnvironmentImpl,
    val consoleOutput: ConsoleOutput = StandardConsoleOutput,
    protected val createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner =
      new ConsoleGrpcAdminCommandRunner(_),
) extends ConsoleEnvironment // TODO(Arne): Generalize this.
    {

  override type Env = CoinEnvironmentImpl
  override type DomainLocalRef = CommunityLocalDomainReference
  override type DomainRemoteRef = CommunityRemoteDomainReference
  override type Status = CommunityCantonStatus

  lazy val validators: Seq[LocalValidatorReference] =
    environment.config.validatorsByString.keys.map(createValidatorReference).toSeq

  private def createValidatorReference(name: String): LocalValidatorReference =
    new LocalValidatorReference(this, name)

  override protected def topLevelValues: Seq[TopLevelValue[_]] = {

    super.topLevelValues ++
      validators.map(v =>
        TopLevelValue(v.name, helpText("validator app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "validators",
        helpText("All validator app instances" + genericNodeReferencesDoc, "Validators"),
        validators,
        Seq("App References"),
      )

  }

  private lazy val health_ = new CommunityCantonHealthAdministration(this)

  @Help.Summary("Environment health inspection")
  @Help.Group("Health")
  override def health: CantonHealthAdministration[CommunityCantonStatus] =
    health_

  override protected def startupOrderPrecedence(instance: LocalInstanceReference): Int =
    instance match {
      case _: LocalDomainReference => 1
      case _: LocalParticipantReference => 2
      case _ => 3
    }

  override protected def domainsTopLevelValue(
      h: TopLevelValue.Partial,
      domains: NodeReferences[
        DomainReference,
        CommunityRemoteDomainReference,
        CommunityLocalDomainReference,
      ],
  ): TopLevelValue[
    NodeReferences[DomainReference, CommunityRemoteDomainReference, CommunityLocalDomainReference]
  ] =
    h(domains)

  override protected def localDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: CommunityLocalDomainReference,
  ): TopLevelValue[CommunityLocalDomainReference] =
    h(d)

  override protected def remoteDomainTopLevelValue(
      h: TopLevelValue.Partial,
      d: CommunityRemoteDomainReference,
  ): TopLevelValue[CommunityRemoteDomainReference] =
    h(d)

  override protected def localDomainHelpItems(
      scope: Set[FeatureFlag],
      localDomain: CommunityLocalDomainReference,
  ): Seq[Help.Item] =
    Help.getItems(localDomain, baseTopic = Seq("$domain"), scope = scope)

  override protected def remoteDomainHelpItems(
      scope: Set[FeatureFlag],
      remoteDomain: CommunityRemoteDomainReference,
  ): Seq[Help.Item] =
    Help.getItems(remoteDomain, baseTopic = Seq("$domain"), scope = scope)

  override protected def createDomainReference(name: String): CommunityLocalDomainReference =
    new CommunityLocalDomainReference(this, name)

  override protected def createRemoteDomainReference(name: String): CommunityRemoteDomainReference =
    new CommunityRemoteDomainReference(this, name)
}
