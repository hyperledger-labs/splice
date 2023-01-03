package com.daml.network.util

import com.daml.network.console.{
  LocalDirectoryAppReference,
  ScanAppBackendReference,
  SplitwiseAppBackendReference,
  SvcAppBackendReference,
  ValidatorAppBackendReference,
  RemoteDirectoryAppReference,
  SplitwiseAppClientReference,
  SvcAppClientReference,
  WalletAppBackendReference,
  WalletAppClientReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId

// TODO(#736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonCoinAppInstanceReferences {

  def svcParty(implicit env: CoinTestConsoleEnvironment): PartyId = scan.getSvcPartyId()

  def svc(implicit env: CoinTestConsoleEnvironment): SvcAppBackendReference = env.svcOpt.getOrElse(
    sys.error("Tried to access the SVC app but it isn't defined in the test's configuration file")
  )
  def remoteSvc(implicit env: CoinTestConsoleEnvironment): SvcAppClientReference =
    env.remoteSvcOpt.getOrElse(
      sys.error(
        "Tried to access the remote SVC app but it isn't defined in the test's configuration file"
      )
    )
  def scan(implicit env: CoinTestConsoleEnvironment): ScanAppBackendReference =
    env.scans.local.headOption.getOrElse(
      sys.error(
        "Tried to access the Scan app but it isn't defined in the test's configuration file"
      )
    )
  def aliceWalletBackend(implicit env: CoinTestConsoleEnvironment): WalletAppBackendReference = wb(
    "aliceWalletBackend"
  )
  def aliceWallet(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference = wc(
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
  def bobWalletBackend(implicit env: CoinTestConsoleEnvironment): WalletAppBackendReference = wb(
    "bobWalletBackend"
  )
  def bobWallet(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference = wc(
    "bobWallet"
  )
  def charlieWallet(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference = wc(
    "charlieWallet"
  )
  def bobValidator(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference = v(
    "bobValidator"
  )
  def directoryValidator(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference =
    v(
      "directoryValidator"
    )
  def splitwiseValidator(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference =
    v(
      "splitwiseValidator"
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

  def aliceSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwiseAppClientReference = rsw(
    "aliceSplitwise"
  )

  def bobSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwiseAppClientReference = rsw(
    "bobSplitwise"
  )

  def charlieSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwiseAppClientReference = rsw(
    "charlieSplitwise"
  )

  def providerSplitwiseBackend(implicit
      env: CoinTestConsoleEnvironment
  ): SplitwiseAppBackendReference = sw(
    "providerSplitwiseBackend"
  )

  def wb(name: String)(implicit env: CoinTestConsoleEnvironment): WalletAppBackendReference =
    env.wallets.local
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def wc(name: String)(implicit env: CoinTestConsoleEnvironment): WalletAppClientReference =
    env.wallets.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def v(name: String)(implicit env: CoinTestConsoleEnvironment): ValidatorAppBackendReference =
    env.validators.local
      .find(_.name == name)
      .getOrElse(sys.error(s"validator [$name] not configured"))

  def rdp(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): RemoteDirectoryAppReference =
    env.directories.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote directory [$name] not configured"))

  def sw(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): SplitwiseAppBackendReference =
    env.splitwises.local
      .find(_.name == name)
      .getOrElse(sys.error(s"local splitwise [$name] not configured"))

  def rsw(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): SplitwiseAppClientReference =
    env.splitwises.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote splitwise [$name] not configured"))
}
