package com.daml.network.environment

import com.daml.network.console.{
  CNNodeAppReference,
  ConsoleHttpCommandRunner,
  DirectoryAppReference,
  LocalCNNodeAppReference,
  LocalDirectoryAppReference,
  RemoteDirectoryAppReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  ScanAppReference,
  SplitwellAppBackendReference,
  SplitwellAppClientReference,
  SplitwellAppReference,
  SvAppBackendReference,
  SvAppClientReference,
  SvcAppBackendReference,
  SvcAppClientReference,
  ValidatorAppBackendReference,
  ValidatorAppClientReference,
  ValidatorAppReference,
  WalletAppClientReference,
}
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.sv.config.RemoteSvAppConfig
import com.daml.network.svc.config.SvcAppClientConfig
import com.daml.network.util.ResourceTemplateDecoder
import com.daml.network.validator.config.ValidatorAppClientConfig
import com.daml.network.wallet.config.WalletAppClientConfig
import com.digitalasset.canton.admin.api.client.data.CommunityCantonStatus
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.console.{
  CantonHealthAdministration,
  CommunityCantonHealthAdministration,
  CommunityLocalDomainReference,
  CommunityRemoteDomainReference,
  ConsoleEnvironment,
  ConsoleEnvironmentBinding,
  ConsoleGrpcAdminCommandRunner,
  ConsoleOutput,
  DomainReference,
  FeatureFlag,
  Help,
  LocalDomainReference,
  LocalInstanceReferenceCommon,
  LocalParticipantReference,
  NodeReferences,
  StandardConsoleOutput,
}

class CNNodeConsoleEnvironment(
    val environment: CNNodeEnvironmentImpl,
    val consoleOutput: ConsoleOutput = StandardConsoleOutput,
    protected val createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner =
      new ConsoleGrpcAdminCommandRunner(_),
) extends ConsoleEnvironment // TODO(#736): Generalize this.
    {

  val packageSignatures = ResourceTemplateDecoder.loadPackageSignaturesFromResources(
    Seq(
      "dar/directory-service-0.1.0.dar",
      "dar/validator-lifecycle-0.1.0.dar",
      "dar/wallet-0.1.0.dar",
      "dar/canton-coin-0.1.1.dar",
      "dar/svc-governance-0.1.0.dar",
    )
  )
  val templateDecoder = new ResourceTemplateDecoder(packageSignatures, environment.loggerFactory)

  lazy val httpCommandRunner: ConsoleHttpCommandRunner = new ConsoleHttpCommandRunner(
    environment,
    environment.config.parameters.timeouts.processing,
    environment.config.parameters.timeouts.console,
  )(this.tracer, templateDecoder)

  override type Env = CNNodeEnvironmentImpl
  override type DomainLocalRef = CommunityLocalDomainReference
  override type DomainRemoteRef = CommunityRemoteDomainReference
  override type Status = CommunityCantonStatus

  def mergeLocalCNNodeInstances(
      locals: Seq[LocalCNNodeAppReference]*
  ): Seq[LocalCNNodeAppReference] =
    locals.flatten

  def mergeRemoteCNNodeInstances(remotes: Seq[CNNodeAppReference]*): Seq[CNNodeAppReference] =
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
    CNNodeAppReference,
    CNNodeAppReference,
    LocalCNNodeAppReference,
  ] = {
    NodeReferences(
      mergeLocalCNNodeInstances(
        fullSvcApps.local,
        appsHostedByValidator.local,
        appsHostedByThirdParty.local,
      ),
      mergeRemoteCNNodeInstances(
        fullSvcApps.remote,
        appsHostedByValidator.remote,
        appsHostedByThirdParty.remote,
      ),
    )
  }

  /* Local apps that are (in the target deployment) operated by the SVC */
  lazy val fullSvcApps = NodeReferences(
    mergeLocalCNNodeInstances(
      // TODO(#4035) svc can be removed when all logic is ported from SvcApp to Sv Apps
      svcOpt.toList,
      // TODO(#4285) SV5 is not hosted by the SVC, we can remove this filtering when sv5 is completed removed
      svs.local.filter(sv => sv.name != "sv5"),
      scans.local,
      directories.local,
    ),
    mergeRemoteCNNodeInstances(
      // TODO(#4035) svc can be removed when all logic is ported from SvcApp to Sv Apps
      remoteSvcOpt.toList,
      // TODO(#4285) SV5 is not hosted by the SVC, we can remove this filtering when sv5 is completed removed
      svs.remote.filter(sv => sv.name != "sv5"),
      scans.remote,
      directories.remote,
    ),
  )

  /* Local apps that are (in the target deployment) operated by the SVC with only Sv1 */
  lazy val minimalSvcApps = NodeReferences(
    mergeLocalCNNodeInstances(
      // TODO(#4035) svc can be removed when all logic is ported from SvcApp to Sv Apps
      svcOpt.toList,
      svs.local.filter(sv => sv.name == "sv1"),
      scans.local,
      directories.local,
    ),
    mergeRemoteCNNodeInstances(
      // TODO(#4035) svc can be removed when all logic is ported from SvcApp to Sv Apps
      remoteSvcOpt.toList,
      svs.remote.filter(sv => sv.name == "sv1"),
      scans.remote,
      directories.remote,
    ),
  )

  /* Local apps that are (in the target deployment) operated by a self-hosted validator */
  lazy val appsHostedByValidator = NodeReferences(
    validators.local,
    mergeRemoteCNNodeInstances(validators.remote, wallets),
  )

  /* Local apps that are (in the target deployment) operated by a third party */
  lazy val appsHostedByThirdParty =
    NodeReferences[
      SplitwellAppReference,
      SplitwellAppClientReference,
      SplitwellAppBackendReference,
    ](
      splitwells.local,
      splitwells.remote,
    )

  lazy val validators: NodeReferences[
    ValidatorAppReference,
    ValidatorAppClientReference,
    ValidatorAppBackendReference,
  ] =
    NodeReferences(
      environment.config.validatorsByString.keys.map(createValidatorReference).toSeq,
      environment.config.validatorAppClients.toSeq.map(createRemoteValidatorReference),
    )

  lazy val scans
      : NodeReferences[ScanAppReference, ScanAppClientReference, ScanAppBackendReference] =
    NodeReferences(
      environment.config.scansByString.keys.map(createScanReference).toSeq,
      environment.config.ScanAppClients.toSeq.map(createRemoteScanReference),
    )

  lazy val svcOpt: Option[SvcAppBackendReference] =
    environment.config.svcsByString.keys
      .map(createSvcReference)
      .headOption

  lazy val remoteSvcOpt: Option[SvcAppClientReference] =
    environment.config.remoteSvcApps.toSeq
      .map(createRemoteSvcReference)
      .headOption

  lazy val svs: NodeReferences[CNNodeAppReference, SvAppClientReference, SvAppBackendReference] =
    NodeReferences(
      environment.config.svsByString.keys.map(createSvBackendReference).toSeq,
      environment.config.svAppClients.toSeq.map(createSvAppClientReference),
    )

  lazy val wallets: Seq[WalletAppClientReference] =
    environment.config.walletAppClients.toSeq.map(createWalletAppClientReference)

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

  lazy val splitwells: NodeReferences[
    SplitwellAppReference,
    SplitwellAppClientReference,
    SplitwellAppBackendReference,
  ] =
    NodeReferences(
      environment.config.splitwellsByString.keys.map(createSplitwellReference).toSeq,
      environment.config.remoteSplitwellsByString.keys.map(createRemoteSplitwellReference).toSeq,
    )

  private def createValidatorReference(name: String): ValidatorAppBackendReference =
    new ValidatorAppBackendReference(this, name)

  private def createRemoteValidatorReference(
      conf: (InstanceName, ValidatorAppClientConfig)
  ): ValidatorAppClientReference =
    new ValidatorAppClientReference(this, conf._1.unwrap, conf._2)

  private def createScanReference(name: String): ScanAppBackendReference =
    new ScanAppBackendReference(this, name)

  private def createRemoteScanReference(
      conf: (InstanceName, ScanAppClientConfig)
  ): ScanAppClientReference =
    new ScanAppClientReference(this, conf._1.unwrap, conf._2)

  private def createSvcReference(name: String): SvcAppBackendReference =
    new SvcAppBackendReference(this, name)

  private def createRemoteSvcReference(
      conf: (InstanceName, SvcAppClientConfig)
  ): SvcAppClientReference =
    new SvcAppClientReference(this, conf._1.unwrap, conf._2)

  private def createSvBackendReference(name: String): SvAppBackendReference =
    new SvAppBackendReference(this, name)

  private def createSvAppClientReference(
      conf: (InstanceName, RemoteSvAppConfig)
  ): SvAppClientReference =
    new SvAppClientReference(this, conf._1.unwrap, conf._2)

  private def createWalletAppClientReference(
      conf: (InstanceName, WalletAppClientConfig)
  ): WalletAppClientReference =
    new WalletAppClientReference(this, conf._1.unwrap, conf._2)

  private def createDirectoryReference(name: String): LocalDirectoryAppReference =
    new LocalDirectoryAppReference(this, name)

  private def createRemoteDirectoryReference(name: String): RemoteDirectoryAppReference =
    new RemoteDirectoryAppReference(
      this,
      name,
      this.environment.config.remoteDirectoriesByString(name),
    )

  private def createSplitwellReference(name: String): SplitwellAppBackendReference =
    new SplitwellAppBackendReference(this, name)

  private def createRemoteSplitwellReference(name: String): SplitwellAppClientReference =
    new SplitwellAppClientReference(this, name, environment.config.remoteSplitwellsByString(name))

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
      svs.local.map(sv =>
        TopLevelValue(sv.name, helpText("local sv app", sv.name), sv, Seq("App References"))
      ) :+ TopLevelValue(
        "svs",
        helpText("All local sv app instances" + genericNodeReferencesDoc, "SVs"),
        svs.local,
        Seq("App References"),
      ) :++
      svs.remote.map(sv =>
        TopLevelValue(
          s"${sv.name}Client",
          helpText("remote sv app", sv.name),
          sv,
          Seq("App References"),
        )
      ) :+ TopLevelValue(
        "remoteSvs",
        helpText("All remote sv app instances" + genericNodeReferencesDoc, "Remote SVs"),
        svs.remote,
        Seq("App References"),
      ) :++
      wallets.map(w =>
        TopLevelValue(w.name, helpText("wallet app user", w.name), w, Seq("App References"))
      ) :+ TopLevelValue(
        "remoteWallets",
        helpText("All wallet app user instances" + genericNodeReferencesDoc, "Wallet Users"),
        wallets,
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
      ) :++ splitwells.local.map(v =>
        TopLevelValue(v.name, helpText("local splitwell app", v.name), v, Seq("App References"))
      ) :++ splitwells.remote.map(v =>
        TopLevelValue(v.name, helpText("remote splitwell app", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "splitwells",
        helpText(
          "All local splitwell instances" + genericNodeReferencesDoc,
          "Splitwells",
        ),
        splitwells.local,
        Seq("App References"),
      ) :+ TopLevelValue(
        "remoteSplitwells",
        helpText(
          "All remote splitwell instances" + genericNodeReferencesDoc,
          "Splitwells",
        ),
        splitwells.remote,
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
        "fullSvcApps",
        helpText("All local apps hosted by the SVC" + genericNodeReferencesDoc, "fullSvcApps"),
        fullSvcApps,
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
  override protected val consoleEnvironmentBindings = new ConsoleEnvironmentBinding()

  @Help.Summary("Environment health inspection")
  @Help.Group("Health")
  override def health: CantonHealthAdministration[CommunityCantonStatus] =
    health_

  override protected def startupOrderPrecedence(instance: LocalInstanceReferenceCommon): Int =
    instance match {
      case _: LocalDomainReference => 1
      case _: LocalParticipantReference => 2
      case _: SvcAppBackendReference => 3
      case _: ScanAppBackendReference => 4
      case _: ValidatorAppBackendReference => 5
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
