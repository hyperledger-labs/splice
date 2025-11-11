package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.config.SpliceInstanceNamesConfig
import org.lfdecentralizedtrust.splice.console.{
  AnsExternalAppClientReference,
  AppBackendReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  SplitwellAppBackendReference,
  SplitwellAppClientReference,
  SvAppBackendReference,
  SvAppClientReference,
  ValidatorAppBackendReference,
  ValidatorAppClientReference,
  WalletAppClientReference,
}
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import com.digitalasset.canton.SynchronizerAlias

// TODO(DACH-NY/canton-network-node#736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonAppInstanceReferences {
  def decentralizedSynchronizerId(implicit env: SpliceTestConsoleEnvironment): SynchronizerId =
    sv1Backend.participantClientWithAdminToken.synchronizers
      .id_of(
        sv1Backend.config.domains.global.alias
      )
      .logical
  def decentralizedSynchronizerAlias(implicit
      env: SpliceTestConsoleEnvironment
  ): SynchronizerAlias =
    sv1Backend.config.domains.global.alias

  def dsoParty(implicit env: SpliceTestConsoleEnvironment): PartyId = sv1ScanBackend.getDsoPartyId()

  def activeSvs(implicit env: SpliceTestConsoleEnvironment): Seq[SvAppBackendReference] =
    env.svs.local

  def sv1Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv1")

  def sv1LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv1Local"
  )

  def sv2Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv2")

  def sv2LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv2Local"
  )

  def sv2OnboardedBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv2Onboarded"
  )

  def sv3Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv3")

  def sv3LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv3Local"
  )

  def sv4Backend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb("sv4")

  def sv4LocalBackend(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv4Local"
  )

  def sv1Client(implicit env: SpliceTestConsoleEnvironment): SvAppClientReference = sv_client("sv1")

  def sv1ScanBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv1Scan"
  )

  def sv1ScanLocalBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference =
    scanb(
      "sv1ScanLocal"
    )

  def sv2ScanLocalBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference =
    scanb(
      "sv2ScanLocal"
    )

  def sv3ScanLocalBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference =
    scanb(
      "sv3ScanLocal"
    )

  def sv4ScanLocalBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference =
    scanb(
      "sv4ScanLocal"
    )

  def sv2ScanBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv2Scan"
  )

  def sv3ScanBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv3Scan"
  )

  def sv4ScanBackend(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv4Scan"
  )

  @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
  def sv1Nodes(implicit env: SpliceTestConsoleEnvironment): Seq[AppBackendReference] =
    Seq(sv1Backend, sv1ScanBackend, sv1ValidatorBackend)

  @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
  def sv2Nodes(implicit env: SpliceTestConsoleEnvironment): Seq[AppBackendReference] =
    Seq(sv2Backend, sv2ScanBackend, sv2ValidatorBackend)

  @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
  def sv3Nodes(implicit env: SpliceTestConsoleEnvironment): Seq[AppBackendReference] =
    Seq(sv3Backend, sv3ScanBackend, sv3ValidatorBackend)

  @SuppressWarnings(Array("org.wartremover.warts.Product", "org.wartremover.warts.Serializable"))
  def sv4Nodes(implicit env: SpliceTestConsoleEnvironment): Seq[AppBackendReference] =
    Seq(sv4Backend, sv4ScanBackend, sv4ValidatorBackend)

  def aliceWalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = uwc(
    "aliceWallet"
  )

  def aliceValidatorWalletClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "aliceValidatorWallet"
  )

  def aliceValidatorWalletLocalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "aliceValidatorLocalWallet"
  )

  def aliceValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "aliceValidator"
  )

  def aliceValidatorLocalBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "aliceValidatorLocal"
  )

  def aliceValidatorClient(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppClientReference =
    vc(
      "aliceValidatorClient"
    )

  def bobWalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = uwc(
    "bobWallet"
  )

  // Note: this uses `wc` instead of `uwc` because we don't suffix the user names of SVs.
  def sv1WalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = wc(
    "sv1Wallet"
  )

  def sv1WalletLocalClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    wc(
      "sv1WalletLocal"
    )

  def bobValidatorWalletClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "bobValidatorWallet"
  )

  def charlieWalletClient(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    uwc(
      "charlieWallet"
    )

  def bobValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "bobValidator"
  )

  def sv1ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv1Validator"
  )

  def sv1ValidatorLocalBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv1ValidatorLocal"
  )

  def sv2ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv2Validator"
  )

  def sv2ValidatorLocalBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv2ValidatorLocal"
  )

  def sv3ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv3Validator"
  )

  def sv4ValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv4Validator"
  )

  def splitwellValidatorBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): ValidatorAppBackendReference =
    v(
      "splitwellValidator"
    )

  def splitwellWalletClient(implicit
      env: SpliceTestConsoleEnvironment
  ): WalletAppClientReference =
    wc(
      "splitwellProviderWallet"
    )

  def aliceAnsExternalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): AnsExternalAppClientReference = rdpe(
    "aliceAns"
  )

  def bobAnsExternalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): AnsExternalAppClientReference = rdpe(
    "bobAns"
  )

  def charlieAnsExternalClient(implicit
      env: SpliceTestConsoleEnvironment
  ): AnsExternalAppClientReference = rdpe(
    "charlieAns"
  )

  def aliceSplitwellClient(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "aliceSplitwell"
  )

  def bobSplitwellClient(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "bobSplitwell"
  )

  def charlieSplitwellClient(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "charlieSplitwell"
  )

  def splitwellBackend(implicit
      env: SpliceTestConsoleEnvironment
  ): SplitwellAppBackendReference = sw(
    "providerSplitwellBackend"
  )

  def svb(name: String)(implicit env: SpliceTestConsoleEnvironment): SvAppBackendReference =
    env.svs.local
      .find(_.name == name)
      .getOrElse(sys.error(s"sv [$name] not configured"))

  def sv_client(name: String)(implicit env: SpliceTestConsoleEnvironment): SvAppClientReference =
    env.svs.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"sv [$name] not configured"))

  // "user wallet client"; we define this separately from wc so we can override it more conveniently
  def uwc(name: String)(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference = wc(
    name
  )

  def wc(name: String)(implicit env: SpliceTestConsoleEnvironment): WalletAppClientReference =
    env.wallets
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def v(name: String)(implicit env: SpliceTestConsoleEnvironment): ValidatorAppBackendReference =
    env.validators.local
      .find(_.name == name)
      .getOrElse(sys.error(s"validator [$name] not configured"))

  def vc(name: String)(implicit env: SpliceTestConsoleEnvironment): ValidatorAppClientReference =
    env.validators.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"validator client [$name] not configured"))

  def rdpe(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): AnsExternalAppClientReference =
    env.externalAns
      .find(_.name == name)
      .getOrElse(sys.error(s"remote external ANS [$name] not configured"))

  def sw(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): SplitwellAppBackendReference =
    env.splitwells.local
      .find(_.name == name)
      .getOrElse(sys.error(s"local splitwell [$name] not configured"))

  def rsw(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): SplitwellAppClientReference =
    env.splitwells.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote splitwell [$name] not configured"))

  def scanb(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): ScanAppBackendReference =
    env.scans.local
      .find(_.name == name)
      .getOrElse(sys.error(s"scan app [$name] not configured"))

  def scancl(
      name: String
  )(implicit env: SpliceTestConsoleEnvironment): ScanAppClientReference =
    env.scans.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"scan app client [$name] not configured"))

  def spliceInstanceNames(implicit env: SpliceTestConsoleEnvironment): SpliceInstanceNamesConfig = {
    // Find any SV or remote scan reference to read a splice instance name config
    env.svs.local.headOption
      .map(_.config.spliceInstanceNames)
      .getOrElse {
        env.scans.remote
          .filter(sv => sv.name == "sv1Scan")
          .headOption
          .map(_.getSpliceInstanceNames())
          .getOrElse(
            env.scans.remote.headOption
              .map(_.getSpliceInstanceNames())
              .getOrElse(
                new SpliceInstanceNamesConfig(
                  "Splice",
                  "https://www.hyperledger.org/hubfs/hyperledgerfavicon.png",
                  "Amulet",
                  "AMT",
                  "Amulet Name Service",
                  "ANS",
                )
              )
          )
      }
  }

  def ansAcronym(implicit env: SpliceTestConsoleEnvironment): String =
    spliceInstanceNames.nameServiceNameAcronym.toLowerCase()

  def amuletName(implicit env: SpliceTestConsoleEnvironment): String =
    spliceInstanceNames.amuletName

  def amuletNameAcronym(implicit env: SpliceTestConsoleEnvironment): String =
    spliceInstanceNames.amuletNameAcronym
}
