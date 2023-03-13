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
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId
import com.daml.network.console.ValidatorAppClientReference

// TODO(#736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonCoinAppInstanceReferences {

  def svcParty(implicit env: CoinTestConsoleEnvironment): PartyId = scan.getSvcPartyId()

  def svc(implicit env: CoinTestConsoleEnvironment): SvcAppBackendReference = env.svcOpt.getOrElse(
    sys.error("Tried to access the SVC app but it isn't defined in the test's configuration file")
  )

  def svcClient(implicit env: CoinTestConsoleEnvironment): SvcAppClientReference =
    env.remoteSvcOpt.getOrElse(
      sys.error(
        "Tried to access the remote SVC app but it isn't defined in the test's configuration file"
      )
    )

  def svs(implicit env: CoinTestConsoleEnvironment): Seq[SvAppBackendReference] =
    Seq(sv1, sv2, sv3, sv4)

  def sv1(implicit env: CoinTestConsoleEnvironment): SvAppBackendReference = svb("sv1")

  def sv2(implicit env: CoinTestConsoleEnvironment): SvAppBackendReference = svb("sv2")

  def sv3(implicit env: CoinTestConsoleEnvironment): SvAppBackendReference = svb("sv3")

  def sv4(implicit env: CoinTestConsoleEnvironment): SvAppBackendReference = svb("sv4")

  def sv5(implicit env: CoinTestConsoleEnvironment): SvAppBackendReference = svb("sv5")

  def scan(implicit env: CoinTestConsoleEnvironment): ScanAppBackendReference =
    env.scans.local.headOption.getOrElse(
      sys.error(
        "Tried to access the Scan app but it isn't defined in the test's configuration file"
      )
    )

  def aliceWalletBackend(implicit env: CoinTestConsoleEnvironment): WalletAppBackendReference = wb(
    "aliceWalletBackend"
  )

  def aliceWallet(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference = uwc(
    "aliceWallet"
  )

  def aliceValidatorWallet(implicit
      env: CoinTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "aliceValidatorWallet"
  )

  def aliceValidator(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "aliceValidator"
  )

  def aliceValidatorClient(implicit env: CoinTestConsoleEnvironment): ValidatorAppClientReference =
    vc(
      "aliceValidatorClient"
    )

  def bobWalletBackend(implicit env: CoinTestConsoleEnvironment): WalletAppBackendReference = wb(
    "bobWalletBackend"
  )

  def splitwellWalletBackend(implicit env: CoinTestConsoleEnvironment): WalletAppBackendReference =
    wb(
      "splitwellWalletBackend"
    )

  def bobWallet(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference = uwc(
    "bobWallet"
  )

  def bobValidatorWallet(implicit
      env: CoinTestConsoleEnvironment
  ): WalletAppClientReference = wc(
    "bobValidatorWallet"
  )

  def charlieWallet(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference = uwc(
    "charlieWallet"
  )

  def bobValidator(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "bobValidator"
  )

  def splitwellValidator(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference =
    v(
      "splitwellValidator"
    )

  def splitwellProviderWallet(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference =
    wc(
      "splitwellProviderWallet"
    )

  def directory(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryAppReference =
    env.directories.local.headOption.getOrElse(
      sys.error(
        "Tried to access the Directory app but it isn't defined in the test's configuration file"
      )
    )

  def aliceDirectory(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteDirectoryAppReference = rdp(
    "aliceDirectory"
  )

  def bobDirectory(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteDirectoryAppReference = rdp(
    "bobDirectory"
  )

  def charlieDirectory(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteDirectoryAppReference = rdp(
    "charlieDirectory"
  )

  def aliceSplitwell(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "aliceSplitwell"
  )

  def bobSplitwell(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "bobSplitwell"
  )

  def charlieSplitwell(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwellAppClientReference = rsw(
    "charlieSplitwell"
  )

  def providerSplitwellBackend(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwellAppBackendReference = sw(
    "providerSplitwellBackend"
  )

  def svb(name: String)(implicit env: CoinTestConsoleEnvironment): SvAppBackendReference =
    env.svs.local
      .find(_.name == name)
      .getOrElse(sys.error(s"sv [$name] not configured"))

  def wb(name: String)(implicit env: CoinTestConsoleEnvironment): WalletAppBackendReference =
    env.wallets.local
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  // "user wallet client"; we define this separately from wc so we can override it more conveniently
  def uwc(name: String)(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference = wc(
    name
  )

  def wc(name: String)(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference =
    env.wallets.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def v(name: String)(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference =
    env.validators.local
      .find(_.name == name)
      .getOrElse(sys.error(s"validator [$name] not configured"))

  def vc(name: String)(implicit env: CoinTestConsoleEnvironment): ValidatorAppClientReference =
    env.validators.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"validator client [$name] not configured"))

  def rdp(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): RemoteDirectoryAppReference =
    env.directories.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote directory [$name] not configured"))

  def sw(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): SplitwellAppBackendReference =
    env.splitwells.local
      .find(_.name == name)
      .getOrElse(sys.error(s"local splitwell [$name] not configured"))

  def rsw(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): SplitwellAppClientReference =
    env.splitwells.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote splitwell [$name] not configured"))
}
