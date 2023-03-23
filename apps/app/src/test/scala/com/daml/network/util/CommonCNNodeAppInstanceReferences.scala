package com.daml.network.util

import com.daml.network.console.{
  LocalDirectoryAppReference,
  RemoteDirectoryAppReference,
  ScanAppBackendReference,
  SplitwellAppBackendReference,
  SplitwellAppClientReference,
  SvAppBackendReference,
  SvcAppBackendReference,
  SvcAppClientReference,
  ValidatorAppBackendReference,
  WalletAppClientReference,
  WalletAppBackendReference,
}
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId
import com.daml.network.console.ValidatorAppClientReference

// TODO(#736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonCNNodeAppInstanceReferences {

  def svcParty(implicit env: CNNodeTestConsoleEnvironment): PartyId = scan.getSvcPartyId()

  def svc(implicit env: CNNodeTestConsoleEnvironment): SvcAppBackendReference =
    env.svcOpt.getOrElse(
      sys.error("Tried to access the SVC app but it isn't defined in the test's configuration file")
    )

  def svcClient(implicit env: CNNodeTestConsoleEnvironment): SvcAppClientReference =
    env.remoteSvcOpt.getOrElse(
      sys.error(
        "Tried to access the remote SVC app but it isn't defined in the test's configuration file"
      )
    )

  def svs(implicit env: CNNodeTestConsoleEnvironment): Seq[SvAppBackendReference] =
    Seq(sv1, sv2, sv3, sv4)

  def sv1(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv1")

  def sv2(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv2")

  def sv3(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv3")

  def sv4(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv4")

  def sv5(implicit env: CNNodeTestConsoleEnvironment): SvAppBackendReference = svb("sv5")

  def scan(implicit env: CNNodeTestConsoleEnvironment): ScanAppBackendReference =
    env.scans.local.headOption.getOrElse(
      sys.error(
        "Tried to access the Scan app but it isn't defined in the test's configuration file"
      )
    )

  def aliceWalletBackend(implicit env: CNNodeTestConsoleEnvironment): WalletAppBackendReference =
    wb(
      "aliceWalletBackend"
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

  def bobWalletBackend(implicit env: CNNodeTestConsoleEnvironment): WalletAppBackendReference = wb(
    "bobWalletBackend"
  )

  def splitwellWalletBackend(implicit
      env: CNNodeTestConsoleEnvironment
  ): WalletAppBackendReference =
    wb(
      "splitwellWalletBackend"
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
  ): LocalDirectoryAppReference =
    env.directories.local.headOption.getOrElse(
      sys.error(
        "Tried to access the Directory app but it isn't defined in the test's configuration file"
      )
    )

  def aliceDirectory(implicit
      env: CNNodeTestConsoleEnvironment
  ): RemoteDirectoryAppReference = rdp(
    "aliceDirectory"
  )

  def bobDirectory(implicit
      env: CNNodeTestConsoleEnvironment
  ): RemoteDirectoryAppReference = rdp(
    "bobDirectory"
  )

  def charlieDirectory(implicit
      env: CNNodeTestConsoleEnvironment
  ): RemoteDirectoryAppReference = rdp(
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

  def wb(name: String)(implicit env: CNNodeTestConsoleEnvironment): WalletAppBackendReference =
    env.wallets.local
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  // "user wallet client"; we define this separately from wc so we can override it more conveniently
  def uwc(name: String)(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference = wc(
    name
  )

  def wc(name: String)(implicit env: CNNodeTestConsoleEnvironment): WalletAppClientReference =
    env.wallets.remote
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
  )(implicit env: CNNodeTestConsoleEnvironment): RemoteDirectoryAppReference =
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
}
