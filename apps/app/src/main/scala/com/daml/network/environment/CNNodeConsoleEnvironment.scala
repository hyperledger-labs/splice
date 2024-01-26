package com.daml.network.environment

import com.daml.network.console.*
import com.daml.network.scan.config.ScanAppClientConfig
import com.daml.network.sv.config.SvAppClientConfig
import com.daml.network.util.ResourceTemplateDecoder
import com.daml.network.validator.config.AppManagerAppClientConfig
import com.daml.network.validator.config.CnsAppExternalClientConfig
import com.daml.network.validator.config.ValidatorAppClientConfig
import com.daml.network.wallet.config.WalletAppClientConfig
import com.digitalasset.canton.admin.api.client.data.CommunityCantonStatus
import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.console.{
  CantonHealthAdministration,
  CommunityCantonHealthAdministration,
  ConsoleEnvironment,
  ConsoleEnvironmentBinding,
  ConsoleGrpcAdminCommandRunner,
  ConsoleOutput,
  Help,
  LocalInstanceReferenceCommon,
  NodeReferences,
  StandardConsoleOutput,
}
import org.apache.pekko.actor.ActorSystem

class CNNodeConsoleEnvironment(
    val environment: CNNodeEnvironmentImpl,
    val consoleOutput: ConsoleOutput = StandardConsoleOutput,
    protected val createAdminCommandRunner: ConsoleEnvironment => ConsoleGrpcAdminCommandRunner =
      new ConsoleGrpcAdminCommandRunner(_),
) extends ConsoleEnvironment // TODO(#736): Generalize this.
    {

  val packageSignatures = ResourceTemplateDecoder.loadPackageSignaturesFromResources(
    DarResources.splitwell.all ++
      DarResources.validatorLifecycle.all ++
      DarResources.wallet.all ++
      DarResources.cantonCoin.all ++
      DarResources.svcGovernance.all
  )
  implicit val actorSystem: ActorSystem = environment.actorSystem
  val templateDecoder = new ResourceTemplateDecoder(packageSignatures, environment.loggerFactory)

  lazy val httpCommandRunner: ConsoleHttpCommandRunner = new ConsoleHttpCommandRunner(
    environment,
    environment.config.parameters.timeouts.processing,
    environment.config.parameters.timeouts.console,
  )(this.tracer, templateDecoder)

  override type Env = CNNodeEnvironmentImpl
  override type Status = CommunityCantonStatus

  def mergeLocalCNNodeInstances(
      locals: Seq[CNNodeAppBackendReference]*
  ): Seq[CNNodeAppBackendReference] =
    locals.flatten

  def mergeRemoteCNNodeInstances(remotes: Seq[CNNodeAppReference]*): Seq[CNNodeAppReference] =
    remotes.flatten

  override lazy val nodes = NodeReferences(
    // this override ensures that config options like manualStart also work for CN apps
    coinNodes.local,
    coinNodes.remote,
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
      validators.local.filter(v => v.name.startsWith("sv")),
    ),
    mergeRemoteCNNodeInstances(
      svs.remote,
      scans.remote,
      validators.remote.filter(v => v.name.startsWith("sv")),
    ),
  )

  /* Local apps that are (in the target deployment) operated by the SVC with only Sv1 */
  lazy val minimalSvcApps = NodeReferences(
    mergeLocalCNNodeInstances(
      svs.local.filter(sv => sv.name == "sv1"),
      scans.local.filter(sv => sv.name == "sv1Scan"),
      validators.local.filter(v => v.name == "sv1Validator"),
    ),
    mergeRemoteCNNodeInstances(
      svs.remote.filter(sv => sv.name == "sv1"),
      scans.remote.filter(sv => sv.name == "sv1Scan"),
      validators.remote.filter(v => v.name == "sv1Validator"),
    ),
  )

  /* Local apps that are (in the target deployment) operated by a self-hosted validator */
  lazy val appsHostedByValidator = NodeReferences(
    validators.local,
    mergeRemoteCNNodeInstances(validators.remote, wallets, externalCns),
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

  lazy val svs: NodeReferences[CNNodeAppReference, SvAppClientReference, SvAppBackendReference] =
    NodeReferences(
      environment.config.svsByString.keys.map(createSvBackendReference).toSeq,
      environment.config.svAppClients.toSeq.map(createSvAppClientReference),
    )

  lazy val wallets: Seq[WalletAppClientReference] =
    environment.config.walletAppClients.toSeq.map(createWalletAppClientReference)

  lazy val appManagers: Seq[AppManagerAppClientReference] =
    environment.config.appManagerAppClients.toSeq.map(createAppManagerAppClientReference)

  lazy val externalCns: Seq[CnsExternalAppClientReference] =
    environment.config.cnsAppExternalClients.toSeq
      .map(createCnsExternalAppClientReference)

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

  private def createCnsExternalAppClientReference(
      conf: (
          InstanceName,
          CnsAppExternalClientConfig,
      )
  ): CnsExternalAppClientReference =
    new CnsExternalAppClientReference(this, conf._1.unwrap, conf._2)

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
      externalCns.map(v =>
        TopLevelValue(
          v.name,
          helpText("cns app clients", v.name),
          v,
          Seq("App References"),
        )
      ) :+ TopLevelValue(
        "cnsClients",
        helpText(
          "All cns app client instances" + genericNodeReferencesDoc,
          "cns app clients",
        ),
        externalCns,
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
      case _: SvAppBackendReference => 1
      case _: ScanAppBackendReference => 2
      case _: ValidatorAppBackendReference => 3
      case _ => 5
    }
}
