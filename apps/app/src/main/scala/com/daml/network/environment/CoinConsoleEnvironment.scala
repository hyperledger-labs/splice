package com.daml.network.environment

import com.daml.network.console.{
  LocalCoinAppReference,
  CoinAppReference,
  DirectoryAppReference,
  LocalDirectoryAppReference,
  LocalScanAppReference,
  LocalSplitwiseAppReference,
  LocalSvcAppReference,
  LocalValidatorAppReference,
  LocalWalletAppReference,
  RemoteDirectoryAppReference,
  RemoteScanAppReference,
  RemoteSplitwiseAppReference,
  RemoteSvcAppReference,
  RemoteValidatorAppReference,
  RemoteWalletAppReference,
  ScanAppReference,
  SplitwiseAppReference,
  ValidatorAppReference,
  WalletAppReference,
}
import com.daml.network.scan.config.RemoteScanAppConfig
import com.daml.network.svc.config.RemoteSvcAppConfig
import com.daml.network.validator.config.RemoteValidatorAppConfig
import com.daml.network.wallet.config.RemoteWalletAppConfig
import com.digitalasset.canton.admin.api.client.data.CommunityCantonStatus
import com.digitalasset.canton.config.RequireTypes.InstanceName
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

  def mergeLocalCoinInstances(locals: Seq[LocalCoinAppReference]*): Seq[LocalCoinAppReference] =
    locals.flatten
  def mergeRemoteCoinInstances(remotes: Seq[CoinAppReference]*): Seq[CoinAppReference] =
    remotes.flatten

  override lazy val nodes = NodeReferences(
    // this override ensures that config options like manualStart also work for CN apps
    mergeLocalInstances(
      participants.local,
      domains.local,
      coinNodes.local,
    ),
    mergeRemoteInstances(
      participants.remote,
      domains.remote,
      coinNodes.remote,
    ),
  )

  lazy val coinNodes: NodeReferences[
    CoinAppReference,
    CoinAppReference,
    LocalCoinAppReference,
  ] = {
    NodeReferences(
      mergeLocalCoinInstances(
        appsHostedBySvc.local,
        appsHostedByValidator.local,
        appsHostedByThirdParty.local,
      ),
      mergeRemoteCoinInstances(
        appsHostedBySvc.remote,
        appsHostedByValidator.remote,
        appsHostedByThirdParty.remote,
      ),
    )
  }

  /* Local apps that are (in the target deployment) operated by the SVC */
  lazy val appsHostedBySvc = NodeReferences(
    mergeLocalCoinInstances(svcOpt.toList, scans.local, directories.local),
    mergeRemoteCoinInstances(remoteSvcOpt.toList, scans.remote, directories.remote),
  )

  /* Local apps that are (in the target deployment) operated by a self-hosted validator */
  lazy val appsHostedByValidator = NodeReferences(
    mergeLocalCoinInstances(validators.local, wallets.local),
    mergeRemoteCoinInstances(validators.remote, wallets.remote),
  )

  /* Local apps that are (in the target deployment) operated by a third party */
  lazy val appsHostedByThirdParty = NodeReferences(
    splitwises.local,
    splitwises.remote,
  )

  lazy val validators: NodeReferences[
    ValidatorAppReference,
    RemoteValidatorAppReference,
    LocalValidatorAppReference,
  ] =
    NodeReferences(
      environment.config.validatorsByString.keys.map(createValidatorReference).toSeq,
      environment.config.remoteValidatorApps.toSeq.map(createRemoteValidatorReference),
    )

  lazy val scans: NodeReferences[ScanAppReference, RemoteScanAppReference, LocalScanAppReference] =
    NodeReferences(
      environment.config.scansByString.keys.map(createScanReference).toSeq,
      environment.config.remoteScanApps.toSeq.map(createRemoteScanReference),
    )

  lazy val svcOpt: Option[LocalSvcAppReference] =
    environment.config.svcsByString.keys
      .map(createSvcReference)
      .headOption

  lazy val remoteSvcOpt: Option[RemoteSvcAppReference] =
    environment.config.remoteSvcApps.toSeq
      .map(createRemoteSvcReference)
      .headOption

  lazy val wallets
      : NodeReferences[WalletAppReference, RemoteWalletAppReference, LocalWalletAppReference] =
    NodeReferences(
      environment.config.walletsByString.keys.map(createWalletReference).toSeq,
      environment.config.remoteWalletApps.toSeq.map(createRemoteWalletReference),
    )

  lazy val directories: NodeReferences[
    DirectoryAppReference,
    RemoteDirectoryAppReference,
    LocalDirectoryAppReference,
  ] =
    NodeReferences(
      environment.config.directoriesByString.keys
        .map(createDirectoryReference)
        .toSeq,
      environment.config.remoteDirectoriesByString.keys
        .map(createRemoteDirectoryReference)
        .toSeq,
    )

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

  private def createRemoteValidatorReference(
      conf: (InstanceName, RemoteValidatorAppConfig)
  ): RemoteValidatorAppReference =
    new RemoteValidatorAppReference(this, conf._1.unwrap, conf._2)

  private def createScanReference(name: String): LocalScanAppReference =
    new LocalScanAppReference(this, name)

  private def createRemoteScanReference(
      conf: (InstanceName, RemoteScanAppConfig)
  ): RemoteScanAppReference =
    new RemoteScanAppReference(this, conf._1.unwrap, conf._2)

  private def createSvcReference(name: String): LocalSvcAppReference =
    new LocalSvcAppReference(this, name)

  private def createRemoteSvcReference(
      conf: (InstanceName, RemoteSvcAppConfig)
  ): RemoteSvcAppReference =
    new RemoteSvcAppReference(this, conf._1.unwrap, conf._2)

  private def createWalletReference(name: String): LocalWalletAppReference =
    new LocalWalletAppReference(this, name)

  private def createRemoteWalletReference(
      conf: (InstanceName, RemoteWalletAppConfig)
  ): RemoteWalletAppReference =
    new RemoteWalletAppReference(this, conf._1.unwrap, conf._2)

  private def createDirectoryReference(name: String): LocalDirectoryAppReference =
    new LocalDirectoryAppReference(this, name)

  private def createRemoteDirectoryReference(
      name: String
  ): RemoteDirectoryAppReference =
    new RemoteDirectoryAppReference(this, name)

  private def createSplitwiseReference(name: String): LocalSplitwiseAppReference =
    new LocalSplitwiseAppReference(this, name)

  private def createRemoteSplitwiseReference(name: String): RemoteSplitwiseAppReference =
    new RemoteSplitwiseAppReference(this, name)

  override protected def topLevelValues: Seq[TopLevelValue[_]] = {

    super.topLevelValues ++
      validators.local.map(v =>
        TopLevelValue(v.name, helpText("local validator app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "validators",
        helpText("All local validator app instances" + genericNodeReferencesDoc, "Validators"),
        validators.local,
        Seq("App References"),
      ) :++
      validators.remote.map(v =>
        TopLevelValue(v.name, helpText("remote validator app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "remoteValidators",
        helpText(
          "All remote validator app instances" + genericNodeReferencesDoc,
          "Remote Validators",
        ),
        validators.remote,
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
        TopLevelValue(w.name, helpText("remote wallet app", w.name), w, Seq("App References"))
      ) :+ TopLevelValue(
        "remoteWallets",
        helpText("All remote wallet app instances" + genericNodeReferencesDoc, "Remote Wallets"),
        wallets.remote,
        Seq("App References"),
      ) :++
      directories.local.map(v =>
        TopLevelValue(
          v.name,
          helpText("local directory app", v.name),
          v,
          Seq("App References"),
        )
      ) :+ TopLevelValue(
        "directories",
        helpText(
          "All local directory app instances" + genericNodeReferencesDoc,
          "Directory apps",
        ),
        directories.local,
        Seq("App References"),
      ) :++
      directories.remote.map(v =>
        TopLevelValue(
          v.name,
          helpText("remote directory app", v.name),
          v,
          Seq("App References"),
        )
      ) :+ TopLevelValue(
        "remoteDirectories",
        helpText(
          "All remote directory app instances" + genericNodeReferencesDoc,
          "Remote directory apps",
        ),
        directories.remote,
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
        .toList :++ remoteSvcOpt
        .map(svc => TopLevelValue(svc.name, helpText("Remote SVC app", svc.name), svc, Seq("SVC")))
        .toList :++ scans.local.headOption
        .map(scan => TopLevelValue(scan.name, helpText("Scan app", scan.name), scan, Seq("Scan")))
        .toList :++ scans.remote.headOption
        .map(scan =>
          TopLevelValue(scan.name, helpText("Remote scan app", scan.name), scan, Seq("Scan"))
        )
        .toList :+ TopLevelValue(
        "appsHostedBySvc",
        helpText("All local apps hosted by the SVC" + genericNodeReferencesDoc, "appsHostedBySvc"),
        appsHostedBySvc,
        Seq("App References"),
      ) :+ TopLevelValue(
        "appsHostedByValidator",
        helpText(
          "All local apps hosted by the self-hosted validator" + genericNodeReferencesDoc,
          "appsHostedByValidator",
        ),
        appsHostedByValidator,
        Seq("App References"),
      ) :+ TopLevelValue(
        "appsHostedByThirdParty",
        helpText(
          "All local apps hosted by a third party" + genericNodeReferencesDoc,
          "appsHostedByThirdParty",
        ),
        appsHostedByThirdParty,
        Seq("App References"),
      ) :+
      TopLevelValue(
        "coinNodes",
        "All Coin nodes excluding standard Canton nodes",
        coinNodes,
        Seq("App references"),
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
      case _: LocalSvcAppReference => 3
      case _: LocalScanAppReference => 4
      case _: LocalValidatorAppReference => 5
      case _ => 6
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
