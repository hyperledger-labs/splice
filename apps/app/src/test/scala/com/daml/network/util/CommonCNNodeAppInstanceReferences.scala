package com.daml.network.util

import com.daml.network.console.{
  AppManagerAppClientReference,
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
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.topology.{DomainId, PartyId}
import com.daml.network.console.CnsExternalAppClientReference
import com.digitalasset.canton.DomainAlias

// TODO(#736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonCNNodeAppInstanceReferences {

  def globalDomainId(implicit env: CNNodeTestConsoleEnvironment): DomainId =
    sv1Backend.participantClientWithAdminToken.domains.id_of(sv1Backend.config.domains.global.alias)
  def globalDomainAlias(implicit env: CNNodeTestConsoleEnvironment): DomainAlias =
    sv1Backend.config.domains.global.alias

  def svcParty(implicit env: CNNodeTestConsoleEnvironment): PartyId = sv1ScanBackend.getSvcPartyId()

  def sv1Backend(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv1")

  def sv1LocalBackend(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv1Local"
  )

  def sv2Backend(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv2")

  def sv2OnboardedBackend(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv2Onboarded"
  )

  def sv3Backend(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv3")

  def sv4Backend(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv4")

  def sv1Client(implicit env: CNNodeTestConsoleEnvironment): SvAppClientReference = svcl("sv1")

  def sv1ScanBackend(implicit env: CNNodeTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv1Scan"
  )

  def sv1ScanLocalBackend(implicit env: CNNodeTestConsoleEnvironment): ScanAppBackendReference =
    scanb(
      "sv1ScanLocal"
    )

  def sv2ScanBackend(implicit env: CNNodeTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv2Scan"
  )

  def aliceWalletClient(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = uwc(
    "aliceWallet"
  )

  def aliceAppManagerClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): AppManagerAppClientReference = uamc(
    "aliceAppManager"
  )

  def aliceValidatorWalletClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "aliceValidatorWallet"
  )

  def aliceValidatorBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "aliceValidator"
  )

  def aliceValidatorLocalBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "aliceValidatorLocal"
  )

  def aliceValidatorClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppClientReference =
    vc(
      "aliceValidatorClient"
    )

  def bobWalletClient(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = uwc(
    "bobWallet"
  )

  // Note: this uses `wc` instead of `uwc` because we don't suffix the user names of SVs.
  def sv1WalletClient(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = wc(
    "sv1Wallet"
  )

  def sv1WalletLocalClient(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference =
    wc(
      "sv1WalletLocal"
    )

  def bobValidatorWalletClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "bobValidatorWallet"
  )

  def charlieWalletClient(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference =
    uwc(
      "charlieWallet"
    )

  def bobValidatorBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "bobValidator"
  )

  def sv1ValidatorBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv1Validator"
  )

  def sv1ValidatorLocalBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv1ValidatorLocal"
  )

  def sv2ValidatorBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv2Validator"
  )

  def sv2ValidatorLocalBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv2ValidatorLocal"
  )

  def sv3ValidatorBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv3Validator"
  )

  def sv4ValidatorBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference = v(
    "sv4Validator"
  )

  def splitwellValidatorBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppBackendReference =
    v(
      "splitwellValidator"
    )

  def splitwellWalletClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): WalletAppClientReference =
    wc(
      "splitwellProviderWallet"
    )

  def aliceCnsExternalClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): CnsExternalAppClientReference = rdpe(
    "aliceCns"
  )

  def bobCnsExternalClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): CnsExternalAppClientReference = rdpe(
    "bobCns"
  )

  def charlieCnsExternalClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): CnsExternalAppClientReference = rdpe(
    "charlieCns"
  )

  def aliceSplitwellClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "aliceSplitwell"
  )

  def bobSplitwellClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "bobSplitwell"
  )

  def charlieSplitwellClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "charlieSplitwell"
  )

  def splitwellBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): SplitwellAppBackendReference = sw(
    "providerSplitwellBackend"
  )

  def svb(name: String)(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference =
    env.svs.local
      .find(_.name == name)
      .getOrElse(sys.error(s"sv [$name] not configured"))

  def svcl(name: String)(implicit env: CNNodeTestConsoleEnvironment): SvAppClientReference =
    env.svs.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"sv [$name] not configured"))

  // "user wallet client"; we define this separately from wc so we can override it more conveniently
  def uwc(name: String)(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = wc(
    name
  )

  // "user app manager client"
  def uamc(name: String)(implicit env: CNNodeTestConsoleEnvironment): AppManagerAppClientReference =
    env.appManagers
      .find(_.name == name)
      .getOrElse(sys.error(s"app manager [$name] not configured"))

  def wc(name: String)(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference =
    env.wallets
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def v(name: String)(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference =
    env.validators.local
      .find(_.name == name)
      .getOrElse(sys.error(s"validator [$name] not configured"))

  def vc(name: String)(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppClientReference =
    env.validators.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"validator client [$name] not configured"))

  def rdpe(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): CnsExternalAppClientReference =
    env.externalCns
      .find(_.name == name)
      .getOrElse(sys.error(s"remote external CNS [$name] not configured"))

  def sw(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): SplitwellAppBackendReference =
    env.splitwells.local
      .find(_.name == name)
      .getOrElse(sys.error(s"local splitwell [$name] not configured"))

  def rsw(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): SplitwellAppClientReference =
    env.splitwells.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote splitwell [$name] not configured"))

  def scanb(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): ScanAppBackendReference =
    env.scans.local
      .find(_.name == name)
      .getOrElse(sys.error(s"scan app [$name] not configured"))

  def scancl(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): ScanAppClientReference =
    env.scans.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"scan app client [$name] not configured"))
}
