// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.environment

import com.digitalasset.canton.config.CantonRequireTypes.InstanceName
import com.digitalasset.canton.console.{
  ConsoleEnvironment,
  ConsoleOutput,
  LocalInstanceReference,
  NodeReferences,
  StandardConsoleOutput,
}
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.daml.lf.typesig.PackageSignature
import org.apache.pekko.actor.ActorSystem
import org.lfdecentralizedtrust.splice.config.SpliceConfig
import org.lfdecentralizedtrust.splice.console.*
import org.lfdecentralizedtrust.splice.scan.config.ScanAppClientConfig
import org.lfdecentralizedtrust.splice.sv.SvAppClientConfig
import org.lfdecentralizedtrust.splice.util.ResourceTemplateDecoder
import org.lfdecentralizedtrust.splice.validator.config.{
  AnsAppExternalClientConfig,
  ValidatorAppClientConfig,
}
import org.lfdecentralizedtrust.splice.wallet.config.WalletAppClientConfig

class SpliceConsoleEnvironment(
    override val environment: SpliceEnvironment,
    val consoleOutput: ConsoleOutput = StandardConsoleOutput,
) extends ConsoleEnvironment // TODO(DACH-NY/canton-network-node#736): Generalize this.
    {

  override type Config = SpliceConfig

  implicit val actorSystem: ActorSystem = environment.actorSystem
  private lazy val templateDecoder = new ResourceTemplateDecoder(
    SpliceConsoleEnvironment.packageSignatures,
    environment.loggerFactory,
  )

  lazy val httpCommandRunner: ConsoleHttpCommandRunner = new ConsoleHttpCommandRunner(
    environment,
    environment.config.parameters.timeouts.console,
    environment.config.parameters.timeouts.requestTimeout,
  )(this.tracer, templateDecoder)

  def mergeLocalSpliceInstances(
      locals: Seq[AppBackendReference]*
  ): Seq[AppBackendReference] =
    locals.flatten

  def mergeRemoteSpliceInstances(remotes: Seq[AppReference]*): Seq[AppReference] =
    remotes.flatten

  override lazy val nodes = NodeReferences(
    // this override ensures that config options like manualStart also work for Splice apps
    amuletNodes.local,
    amuletNodes.remote,
  )

  lazy val amuletNodes: NodeReferences[
    AppReference,
    AppReference,
    AppBackendReference,
  ] = {
    NodeReferences(
      mergeLocalSpliceInstances(
        fullDsoApps.local,
        appsHostedByValidator.local,
        appsHostedByThirdParty.local,
      ),
      mergeRemoteSpliceInstances(
        fullDsoApps.remote,
        appsHostedByValidator.remote,
        appsHostedByThirdParty.remote,
      ),
    )
  }

  /* Local apps that are (in the target deployment) operated by the DSO */
  lazy val fullDsoApps = NodeReferences(
    mergeLocalSpliceInstances(
      svs.local,
      scans.local,
      validators.local.filter(v => v.name.startsWith("sv")),
    ),
    mergeRemoteSpliceInstances(
      svs.remote,
      scans.remote,
      validators.remote.filter(v => v.name.startsWith("sv")),
    ),
  )

  /* Local apps that are (in the target deployment) operated by the DSO with only Sv1 */
  lazy val minimalDsoApps = NodeReferences(
    mergeLocalSpliceInstances(
      svs.local.filter(sv => sv.name == "sv1"),
      scans.local.filter(sv => sv.name == "sv1Scan"),
      validators.local.filter(v => v.name == "sv1Validator"),
    ),
    mergeRemoteSpliceInstances(
      svs.remote.filter(sv => sv.name == "sv1"),
      scans.remote.filter(sv => sv.name == "sv1Scan"),
      validators.remote.filter(v => v.name == "sv1Validator"),
    ),
  )

  /* Local apps that are (in the target deployment) operated by a self-hosted validator */
  lazy val appsHostedByValidator = NodeReferences(
    validators.local,
    mergeRemoteSpliceInstances(validators.remote, wallets, externalAns),
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

  lazy val svs: NodeReferences[AppReference, SvAppClientReference, SvAppBackendReference] =
    NodeReferences(
      environment.config.svsByString.keys.map(createSvBackendReference).toSeq,
      environment.config.svAppClients.toSeq.map(createSvAppClientReference),
    )

  lazy val wallets: Seq[WalletAppClientReference] =
    environment.config.walletAppClients.toSeq.map(createWalletAppClientReference)

  lazy val externalAns: Seq[AnsExternalAppClientReference] =
    environment.config.ansAppExternalClients.toSeq
      .map(createAnsExternalAppClientReference)

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

  private def createAnsExternalAppClientReference(
      conf: (
          InstanceName,
          AnsAppExternalClientConfig,
      )
  ): AnsExternalAppClientReference =
    new AnsExternalAppClientReference(this, conf._1.unwrap, conf._2)

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
      externalAns.map(v =>
        TopLevelValue(
          v.name,
          helpText("ans app clients", v.name),
          v,
          Seq("App References"),
        )
      ) :+ TopLevelValue(
        "ansClients",
        helpText(
          "All ans app client instances" + genericNodeReferencesDoc,
          "ans app clients",
        ),
        externalAns,
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
        "fullDsoApps",
        helpText("All local apps hosted by the DSO" + genericNodeReferencesDoc, "fullDsoApps"),
        fullDsoApps,
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
        "amuletNodes",
        "All Amulet nodes excluding standard Canton nodes",
        amuletNodes,
        Seq("App references"),
      )

  }

  override def startupOrderPrecedence(instance: LocalInstanceReference): Int =
    instance match {
      case _: SvAppBackendReference => 1
      case _: ScanAppBackendReference => 2
      case _: ValidatorAppBackendReference => 3
      case _ => 5
    }
}

object SpliceConsoleEnvironment {

  private lazy val packageSignatures: Map[PackageId, PackageSignature] =
    ResourceTemplateDecoder.loadPackageSignaturesFromResources(
      DarResources.TokenStandard.allPackageResources.flatMap(_.all) ++
        DarResources.splitwell.all ++
        DarResources.validatorLifecycle.all ++
        DarResources.wallet.all ++
        DarResources.amulet.all ++
        DarResources.dsoGovernance.all
    )

}
