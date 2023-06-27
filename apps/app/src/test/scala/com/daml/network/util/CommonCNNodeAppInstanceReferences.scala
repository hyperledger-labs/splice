package com.daml.network.util

import com.daml.network.console.{
  DirectoryAppBackendReference,
  DirectoryAppClientReference,
  ScanAppBackendReference,
  ScanAppClientReference,
  SplitwellAppBackendReference,
  SplitwellAppClientReference,
  SvAppBackendReference,
  SvAppClientReference,
  SvcAppBackendReference,
  SvcAppClientReference,
  ValidatorAppBackendReference,
  ValidatorAppClientReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId

// TODO(#736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonCNNodeAppInstanceReferences {

  def svcParty(implicit env: CNNodeTestConsoleEnvironment): PartyId = sv1Scan.getSvcPartyId()

  def svc(implicit env: CNNodeTestConsoleEnvironment): SvcAppBackendReference =
    env.svcOpt.getOrElse(
      sys.error("Tried to access the SVC app but it isn't defined in the test's configuration file")
    )

  def svcClient(implicit env: CNNodeTestConsoleEnvironment): SvcAppClientReference =
    env.svcClientOpt.getOrElse(
      sys.error(
        "Tried to access the SVC app client but it isn't defined in the test's configuration file"
      )
    )

  def sv1(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv1")

  def sv2(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv2")

  def sv2Onboarded(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb(
    "sv2Onboarded"
  )

  def sv3(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv3")

  def sv4(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv4")

  def sv1Client(implicit env: CNNodeTestConsoleEnvironment): SvAppClientReference = svcl("sv1")

  def sv1Scan(implicit env: CNNodeTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv1Scan"
  )

  def sv2Scan(implicit env: CNNodeTestConsoleEnvironment): ScanAppBackendReference = scanb(
    "sv2Scan"
  )

  def aliceWallet(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = uwc(
    "aliceWallet"
  )

  def aliceValidatorWallet(implicit
      env: CNNodeTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "aliceValidatorWallet"
  )

  def aliceValidator(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "aliceValidator"
  )

  def aliceValidatorClient(implicit
      env: CNNodeTestConsoleEnvironment
  ): ValidatorAppClientReference =
    vc(
      "aliceValidatorClient"
    )

  def bobWallet(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = uwc(
    "bobWallet"
  )

  // Note: this uses `wc` instead of `uwc` because we don't suffix the user names of SVs.
  def sv1Wallet(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = wc(
    "sv1Wallet"
  )

  def bobValidatorWallet(implicit
      env: CNNodeTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "bobValidatorWallet"
  )

  def charlieWallet(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = uwc(
    "charlieWallet"
  )

  def bobValidator(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "bobValidator"
  )

  def sv1Validator(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "sv1Validator"
  )

  def sv2Validator(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "sv2Validator"
  )

  def sv3Validator(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "sv3Validator"
  )

  def sv4Validator(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "sv4Validator"
  )

  def splitwellValidator(implicit env: CNNodeTestConsoleEnvironment): ValidatorAppBackendReference =
    v(
      "splitwellValidator"
    )

  def splitwellProviderWallet(implicit
      env: CNNodeTestConsoleEnvironment
  ): WalletAppClientReference =
    wc(
      "splitwellProviderWallet"
    )

  def directory(implicit
      env: CNNodeTestConsoleEnvironment
  ): DirectoryAppBackendReference =
    env.directories.local.headOption.getOrElse(
      sys.error(
        "Tried to access the Directory app but it isn't defined in the test's configuration file"
      )
    )

  def aliceDirectory(implicit
      env: CNNodeTestConsoleEnvironment
  ): DirectoryAppClientReference = rdp(
    "aliceDirectory"
  )

  def bobDirectory(implicit
      env: CNNodeTestConsoleEnvironment
  ): DirectoryAppClientReference = rdp(
    "bobDirectory"
  )

  def charlieDirectory(implicit
      env: CNNodeTestConsoleEnvironment
  ): DirectoryAppClientReference = rdp(
    "charlieDirectory"
  )

  def aliceSplitwell(implicit
      env: CNNodeTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "aliceSplitwell"
  )

  def bobSplitwell(implicit
      env: CNNodeTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "bobSplitwell"
  )

  def charlieSplitwell(implicit
      env: CNNodeTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "charlieSplitwell"
  )

  def providerSplitwellBackend(implicit
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

  def rdp(
      name: String
  )(implicit env: CNNodeTestConsoleEnvironment): DirectoryAppClientReference =
    env.directories.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote directory [$name] not configured"))

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
