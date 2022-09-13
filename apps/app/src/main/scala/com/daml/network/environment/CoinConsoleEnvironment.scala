package com.daml.network.environment

import com.daml.network.console.{
  LocalDirectoryProviderAppReference,
  LocalDirectoryUserAppReference,
  LocalScanAppReference,
  LocalSplitwiseAppReference,
  LocalSvcAppReference,
  LocalValidatorAppReference,
  LocalWalletAppReference,
  RemoteSplitwiseAppReference,
  RemoteSvcAppReference,
  RemoteWalletAppReference,
  SplitwiseAppReference,
  WalletAppReference,
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
) extends ConsoleEnvironment // TODO(i736): Generalize this.
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
      scanOpt.toList,
      wallets.local,
      directoryProviders,
      directoryUsers,
      splitwises.local,
    ),
    mergeRemoteInstances(
      participants.remote,
      domains.remote,
      wallets.remote,
      splitwises.remote,
    ),
  )

  lazy val validators: Seq[LocalValidatorAppReference] =
    environment.config.validatorsByString.keys.map(createValidatorReference).toSeq

  lazy val scanOpt: Option[LocalScanAppReference] =
    environment.config.scansByString.keys.map(createScanReference).headOption

  lazy val svcOpt: Option[LocalSvcAppReference] =
    environment.config.svcsByString.keys
      .map(createSvcReference)
      .headOption

  lazy val remoteSvcOpt: Option[RemoteSvcAppReference] =
    environment.config.remoteSvcsByString.keys
      .map(createRemoteSvcReference)
      .headOption

  lazy val wallets
      : NodeReferences[WalletAppReference, RemoteWalletAppReference, LocalWalletAppReference] =
    NodeReferences(
      environment.config.walletsByString.keys.map(createWalletReference).toSeq,
      environment.config.remoteWalletsByString.keys.map(createRemoteWalletReference).toSeq,
    )

  lazy val directoryProviders: Seq[LocalDirectoryProviderAppReference] =
    environment.config.directoryProvidersByString.keys.map(createDirectoryProviderReference).toSeq

  lazy val directoryUsers: Seq[LocalDirectoryUserAppReference] =
    environment.config.directoryUsersByString.keys.map(createDirectoryUserReference).toSeq

  lazy val splitwises: NodeReferences[
    SplitwiseAppReference,
    RemoteSplitwiseAppReference,
    LocalSplitwiseAppReference,
  ] =
    NodeReferences(
      environment.config.splitwisesByString.keys.map(createSplitwiseReference).toSeq,
      environment.config.remoteSplitwisesByString.keys.map(createRemoteSplitwiseReference).toSeq,
    )

  private def createValidatorReference(name: String): LocalValidatorAppReference =
    new LocalValidatorAppReference(this, name)

  private def createScanReference(name: String): LocalScanAppReference =
    new LocalScanAppReference(this, name)

  private def createSvcReference(name: String): LocalSvcAppReference =
    new LocalSvcAppReference(this, name)

  private def createRemoteSvcReference(name: String): RemoteSvcAppReference =
    new RemoteSvcAppReference(this, name)

  private def createWalletReference(name: String): LocalWalletAppReference =
    new LocalWalletAppReference(this, name)

  private def createRemoteWalletReference(name: String): RemoteWalletAppReference =
    new RemoteWalletAppReference(this, name)

  private def createDirectoryProviderReference(name: String): LocalDirectoryProviderAppReference =
    new LocalDirectoryProviderAppReference(this, name)

  private def createDirectoryUserReference(name: String): LocalDirectoryUserAppReference =
    new LocalDirectoryUserAppReference(this, name)

  private def createSplitwiseReference(name: String): LocalSplitwiseAppReference =
    new LocalSplitwiseAppReference(this, name)

  private def createRemoteSplitwiseReference(name: String): RemoteSplitwiseAppReference =
    new RemoteSplitwiseAppReference(this, name)

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
      wallets.local.map(w =>
        TopLevelValue(w.name, helpText("local wallet app", w.name), w, Seq("App References"))
      ) :+ TopLevelValue(
        "wallets",
        helpText("All local wallet app instances" + genericNodeReferencesDoc, "Wallets"),
        wallets.local,
        Seq("App References"),
      ) :++
      wallets.remote.map(w =>
        TopLevelValue(w.name, helpText("local wallet app", w.name), w, Seq("App References"))
      ) :+ TopLevelValue(
        "remoteWallets",
        helpText("All remote wallet app instances" + genericNodeReferencesDoc, "Remote Wallets"),
        wallets.remote,
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
      ) :++ splitwises.local.map(v =>
        TopLevelValue(v.name, helpText("local splitwise app", v.name), v, Seq("App References"))
      ) :++ splitwises.remote.map(v =>
        TopLevelValue(v.name, helpText("remote splitwise app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "splitwises",
        helpText(
          "All local splitwise instances" + genericNodeReferencesDoc,
          "Splitwises",
        ),
        splitwises.local,
        Seq("App References"),
      ) :+ TopLevelValue(
        "remoteSplitwises",
        helpText(
          "All remote splitwise instances" + genericNodeReferencesDoc,
          "Splitwises",
        ),
        splitwises.remote,
        Seq("App References"),
      ) :++ svcOpt
        .map(svc => TopLevelValue(svc.name, helpText("SVC app", svc.name), svc, Seq("SVC")))
        .toList :++ scanOpt
        .map(scan => TopLevelValue(scan.name, helpText("Scan app", scan.name), scan, Seq("Scan")))
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
      case _: LocalSvcAppReference => 3
      case _ => 4
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
