package com.daml.network.environment

import com.daml.network.console.{
  LocalDirectoryProviderAppReference,
  LocalDirectoryUserAppReference,
  LocalSvcAppReference,
  LocalValidatorAppReference,
  LocalWalletAppReference,
}
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

  override lazy val nodes = NodeReferences(
    // this override ensures that config options like manualStart also work for CN apps
    mergeLocalInstances(
      participants.local,
      domains.local,
      validators,
      svcOpt.toList,
      wallets,
      directoryProviders,
      directoryUsers,
    ),
    mergeRemoteInstances(participants.remote, domains.remote),
  )

  lazy val validators: Seq[LocalValidatorAppReference] =
    environment.config.validatorsByString.keys.map(createValidatorReference).toSeq

  lazy val svcOpt: Option[LocalSvcAppReference] =
    environment.config.svcsByString.keys
      .map(createSvcReference)
      .headOption

  lazy val wallets: Seq[LocalWalletAppReference] =
    environment.config.walletsByString.keys.map(createWalletReference).toSeq

  lazy val directoryProviders: Seq[LocalDirectoryProviderAppReference] =
    environment.config.directoryProvidersByString.keys.map(createDirectoryProviderReference).toSeq

  lazy val directoryUsers: Seq[LocalDirectoryUserAppReference] =
    environment.config.directoryUsersByString.keys.map(createDirectoryUserReference).toSeq

  private def createValidatorReference(name: String): LocalValidatorAppReference =
    new LocalValidatorAppReference(this, name)

  private def createSvcReference(name: String): LocalSvcAppReference =
    new LocalSvcAppReference(this, name)

  private def createWalletReference(name: String): LocalWalletAppReference =
    new LocalWalletAppReference(this, name)

  private def createDirectoryProviderReference(name: String): LocalDirectoryProviderAppReference =
    new LocalDirectoryProviderAppReference(this, name)

  private def createDirectoryUserReference(name: String): LocalDirectoryUserAppReference =
    new LocalDirectoryUserAppReference(this, name)

  override protected def topLevelValues: Seq[TopLevelValue[_]] = {

    super.topLevelValues ++
      validators.map(v =>
        TopLevelValue(v.name, helpText("validator app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "validators",
        helpText("All validator app instances" + genericNodeReferencesDoc, "Validators"),
        validators,
        Seq("App References"),
      ) :++
      wallets.map(w =>
        TopLevelValue(w.name, helpText("wallet app", w.name), w, Seq("App References"))
      ) :+ TopLevelValue(
        "wallets",
        helpText("All wallet app instances" + genericNodeReferencesDoc, "Wallets"),
        wallets,
        Seq("App References"),
      ) :++
      directoryProviders.map(v =>
        TopLevelValue(v.name, helpText("directory provider app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "directoryProviders",
        helpText(
          "All directory provider app instances" + genericNodeReferencesDoc,
          "Directory providers",
        ),
        directoryProviders,
        Seq("App References"),
      ) :++
      directoryUsers.map(v =>
        TopLevelValue(v.name, helpText("directory user app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "directoryUsers",
        helpText(
          "All directory user app instances" + genericNodeReferencesDoc,
          "Directory users",
        ),
        directoryUsers,
        Seq("App References"),
      ) :++ svcOpt
        .map(svc => TopLevelValue(svc.name, helpText("SVC app", svc.name), svc, Seq("SVC")))
        .toList
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
