package com.daml.network.environment

import com.daml.network.console.*
import com.daml.network.directory.config.DirectoryAppExternalClientConfig
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.sv.config.SvAppClientConfig
import com.daml.network.util.ResourceTemplateDecoder
import com.daml.network.validator.config.AppManagerAppClientConfig
import com.daml.network.validator.config.ValidatorAppClientConfig
import com.daml.network.wallet.config.WalletAppClientConfig
import com.digitalasset.canton.admin.api.client.data.CommunityCantonStatus
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.config.RequireTypes.PositiveInt
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
import com.digitalasset.canton.lifecycle.RunOnShutdown
import com.digitalasset.canton.topology.store.TopologyStoreId.AuthorizedStore

class CNNodeConsoleEnvironment(
    val environment: CNNodeEnvironmentImpl,
    val consoleOutput: ConsoleOutput = StandardConsoleOutput,
    protected val createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner =
      new ConsoleGrpcAdminCommandRunner(_),
) extends ConsoleEnvironment // TODO(#736): Generalize this.
    {

  val packageSignatures = ResourceTemplateDecoder.loadPackageSignaturesFromResources(
    DarResources.directoryService.all ++
      DarResources.splitwell.all ++
      DarResources.validatorLifecycle.all ++
      DarResources.wallet.all ++
      DarResources.cantonCoin.all ++
      DarResources.svcGovernance.all
  )
  implicit val actorSystem = environment.actorSystem
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
      locals: Seq[CNNodeAppBackendReference]*
  ): Seq[CNNodeAppBackendReference] =
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
    CNNodeAppBackendReference,
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
      svs.local,
      scans.local,
      directories.local,
      validators.local.filter(v => v.name.startsWith("sv")),
    ),
    mergeRemoteCNNodeInstances(
      svs.remote,
      scans.remote,
      directories.remote,
      validators.remote.filter(v => v.name.startsWith("sv")),
    ),
  )

  /* Local apps that are (in the target deployment) operated by the SVC with only Sv1 */
  lazy val minimalSvcApps = NodeReferences(
    mergeLocalCNNodeInstances(
      svs.local.filter(sv => sv.name == "sv1"),
      scans.local.filter(sv => sv.name == "sv1Scan"),
      validators.local.filter(v => v.name == "sv1Validator"),
      directories.local,
    ),
    mergeRemoteCNNodeInstances(
      svs.remote.filter(sv => sv.name == "sv1"),
      scans.remote.filter(sv => sv.name == "sv1Scan"),
      validators.remote.filter(v => v.name == "sv1Validator"),
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
      environment.config.scanAppClients.toSeq.map(createRemoteScanReference),
    )

  lazy val svs: NodeReferences[CNNodeAppReference, SvAppClientReference, SvAppBackendReference] = {
    val references
        : NodeReferences[CNNodeAppReference, SvAppClientReference, SvAppBackendReference] =
      NodeReferences(
        environment.config.svsByString.keys.map(createSvBackendReference).toSeq,
        environment.config.svAppClients.toSeq.map(createSvAppClientReference),
      )

    /** The unionspace is reset to contain only sv1 after each env is used
      * When onboarding SVs their participant namespace is added to the unionspace, so for a "clean" test we have to remove them
      * We cannot just drop the unionspace because the global domain is owned by it
      */
    runOnShutdown_(new RunOnShutdown {
      override def name: String = "reset unionspace"

      override def done: Boolean = false

      override def run(): Unit = {
        logger.info("Resetting unionspace to contain only sv1")
        // reset only if sv1 was started
        val activeSv = references.local
          .filter(_.is_running)
        activeSv
          .find(_.name == "sv1")
          .foreach { sv1 =>
            val svcInfo = sv1.getSvcInfo()
            val unionspace = svcInfo.svcParty.uid.namespace
            val svParty = svcInfo.svParty.uid.namespace
            val existingUnionspace = sv1.participantClientWithAdminToken.topology.unionspaces
              .list(
                AuthorizedStore.filterName,
                filterNamespace = unionspace.toProtoPrimitive,
              )
              .headOption
              .getOrElse(throw new IllegalArgumentException("Unionspace not found"))
            if (existingUnionspace.item.owners.exists(_ != svParty))
              sv1.participantClientWithAdminToken.topology.unionspaces
                .propose(
                  Set(svParty.fingerprint),
                  PositiveInt.one,
                  AuthorizedStore.filterName,
                  serial = Some(existingUnionspace.context.serial + PositiveInt.one),
                )
                .discard
          }
      }
    })
    references
  }

  lazy val wallets: Seq[WalletAppClientReference] =
    environment.config.walletAppClients.toSeq.map(createWalletAppClientReference)

  lazy val appManagers: Seq[AppManagerAppClientReference] =
    environment.config.appManagerAppClients.toSeq.map(createAppManagerAppClientReference)

  lazy val externalDirectories: Seq[DirectoryExternalAppClientReference] =
    environment.config.directoryAppExternalClients.toSeq
      .map(createDirectoryExternalAppClientReference)

  lazy val directories: NodeReferences[
    DirectoryAppReference,
    DirectoryAppClientReference,
    DirectoryAppBackendReference,
  ] =
    NodeReferences(
      environment.config.directoriesByString.keys
        .map(createDirectoryReference)
        .toSeq,
      environment.config.directoryClientsByString.keys
        .map(createDirectoryClientReference)
        .toSeq,
    )

  lazy val splitwells: NodeReferences[
    SplitwellAppReference,
    SplitwellAppClientReference,
    SplitwellAppBackendReference,
  ] =
    NodeReferences(
      environment.config.splitwellsByString.keys.map(createSplitwellReference).toSeq,
      environment.config.splitwellClientsByString.keys.map(createRemoteSplitwellReference).toSeq,
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

  private def createSvBackendReference(name: String): SvAppBackendReference =
    new SvAppBackendReference(this, name)

  private def createSvAppClientReference(
      conf: (InstanceName, SvAppClientConfig)
  ): SvAppClientReference =
    new SvAppClientReference(this, conf._1.unwrap, conf._2)

  private def createWalletAppClientReference(
      conf: (InstanceName, WalletAppClientConfig)
  ): WalletAppClientReference =
    new WalletAppClientReference(this, conf._1.unwrap, conf._2)

  private def createAppManagerAppClientReference(
      conf: (InstanceName, AppManagerAppClientConfig)
  ): AppManagerAppClientReference =
    new AppManagerAppClientReference(this, conf._1.unwrap, conf._2)

  private def createDirectoryReference(name: String): DirectoryAppBackendReference =
    new DirectoryAppBackendReference(this, name)

  private def createDirectoryClientReference(name: String): DirectoryAppClientReference =
    new DirectoryAppClientReference(
      this,
      name,
      this.environment.config.directoryClientsByString(name),
    )

  private def createDirectoryExternalAppClientReference(
      conf: (
          InstanceName,
          DirectoryAppExternalClientConfig,
      )
  ): DirectoryExternalAppClientReference =
    new DirectoryExternalAppClientReference(this, conf._1.unwrap, conf._2)

  private def createSplitwellReference(name: String): SplitwellAppBackendReference =
    new SplitwellAppBackendReference(this, name)

  private def createRemoteSplitwellReference(name: String): SplitwellAppClientReference =
    new SplitwellAppClientReference(this, name, environment.config.splitwellClientsByString(name))

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
        TopLevelValue(v.name, helpText("validator app client", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "validatorClients",
        helpText(
          "All validator app client instances" + genericNodeReferencesDoc,
          "Validator Clients",
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
          helpText("sv app client", sv.name),
          sv,
          Seq("App References"),
        )
      ) :+ TopLevelValue(
        "svClients",
        helpText("All sv app client instances" + genericNodeReferencesDoc, "SV clients"),
        svs.remote,
        Seq("App References"),
      ) :++
      wallets.map(w =>
        TopLevelValue(w.name, helpText("wallet app user", w.name), w, Seq("App References"))
      ) :+ TopLevelValue(
        "walletClients",
        helpText("All wallet app user instances" + genericNodeReferencesDoc, "Wallet Users"),
        wallets,
        Seq("App References"),
      ) :++
      appManagers.map(w =>
        TopLevelValue(w.name, helpText("app manager app user", w.name), w, Seq("App References"))
      ) :+ TopLevelValue(
        "appManagerClients",
        helpText(
          "All app manager app user instances" + genericNodeReferencesDoc,
          "App Manager Users",
        ),
        appManagers,
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
          helpText("directory app clients", v.name),
          v,
          Seq("App References"),
        )
      ) :+ TopLevelValue(
        "directoryClients",
        helpText(
          "All directory app client instances" + genericNodeReferencesDoc,
          "directory app clients",
        ),
        directories.remote,
        Seq("App References"),
      ) :++ splitwells.local.map(v =>
        TopLevelValue(v.name, helpText("local splitwell app", v.name), v, Seq("App References"))
      ) :++ splitwells.remote.map(v =>
        TopLevelValue(v.name, helpText("splitwell app client", v.name), v, Seq("App References"))
      ) :+ TopLevelValue(
        "splitwells",
        helpText(
          "All local splitwell instances" + genericNodeReferencesDoc,
          "Splitwells",
        ),
        splitwells.local,
        Seq("App References"),
      ) :+ TopLevelValue(
        "splitwellClients",
        helpText(
          "All splitwell client instances" + genericNodeReferencesDoc,
          "Splitwells",
        ),
        splitwells.remote,
        Seq("App References"),
      ) :++ scans.local.headOption
        .map(scan => TopLevelValue(scan.name, helpText("Scan app", scan.name), scan, Seq("Scan")))
        .toList :++ scans.remote.headOption
        .map(scan =>
          TopLevelValue(scan.name, helpText("scan app client", scan.name), scan, Seq("Scan"))
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
      case _: ScanAppBackendReference => 3
      case _: ValidatorAppBackendReference => 4
      case _ => 5
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
    new CommunityLocalDomainReference(this, name, environment.executionContext)

  override protected def createRemoteDomainReference(name: String): CommunityRemoteDomainReference =
    new CommunityRemoteDomainReference(this, name)

}
