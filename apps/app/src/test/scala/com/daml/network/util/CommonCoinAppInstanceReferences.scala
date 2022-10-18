package com.daml.network.util

import com.daml.network.console.{
  LocalDirectoryAppReference,
  LocalScanAppReference,
  LocalSplitwiseAppReference,
  LocalSvcAppReference,
  LocalValidatorAppReference,
  LocalWalletAppReference,
  RemoteDirectoryAppReference,
  RemoteSplitwiseAppReference,
  RemoteSvcAppReference,
  RemoteWalletAppReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId

// TODO(i736): these should eventually be defined analogue to Canton's `participant1` references etc
// however, this is likely only possible once we depend on Canton as a library
trait CommonCoinAppInstanceReferences {

  def svcParty(implicit env: CoinTestConsoleEnvironment): PartyId = scan.getSvcPartyId()

  def svc(implicit env: CoinTestConsoleEnvironment): LocalSvcAppReference = env.svcOpt.getOrElse(
    sys.error("Tried to access the SVC app but it isn't defined in the test's configuration file")
  )
  def remoteSvc(implicit env: CoinTestConsoleEnvironment): RemoteSvcAppReference =
    env.remoteSvcOpt.getOrElse(
      sys.error(
        "Tried to access the remote SVC app but it isn't defined in the test's configuration file"
      )
    )
  def scan(implicit env: CoinTestConsoleEnvironment): LocalScanAppReference =
    env.scans.local.headOption.getOrElse(
      sys.error(
        "Tried to access the Scan app but it isn't defined in the test's configuration file"
      )
    )
  def aliceWallet(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w(
    "aliceWallet"
  )
  def aliceRemoteWallet(implicit env: CoinTestConsoleEnvironment): RemoteWalletAppReference = rw(
    "aliceRemoteWallet"
  )
  def aliceValidatorRemoteWallet(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteWalletAppReference = rw(
    "aliceValidatorRemoteWallet"
  )
  def aliceValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "aliceValidator"
  )
  def bobWallet(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w(
    "bobWallet"
  )
  def charlieWallet(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w(
    "charlieWallet"
  )
  def bobRemoteWallet(implicit env: CoinTestConsoleEnvironment): RemoteWalletAppReference = rw(
    "bobRemoteWallet"
  )
  def charlieRemoteWallet(implicit env: CoinTestConsoleEnvironment): RemoteWalletAppReference = rw(
    "charlieRemoteWallet"
  )
  def bobValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "bobValidator"
  )
  def directoryValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "directoryValidator"
  )
  def splitwiseValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
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
  ): RemoteSplitwiseAppReference = rsw(
    "aliceSplitwise"
  )

  def bobSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteSplitwiseAppReference = rsw(
    "bobSplitwise"
  )

  def charlieSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteSplitwiseAppReference = rsw(
    "charlieSplitwise"
  )

  def providerSplitwiseBackend(implicit
      env: CoinTestConsoleEnvironment
  ): LocalSplitwiseAppReference = sw(
    "providerSplitwiseBackend"
  )

  def w(name: String)(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference =
    env.wallets.local
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def rw(name: String)(implicit env: CoinTestConsoleEnvironment): RemoteWalletAppReference =
    env.wallets.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def v(name: String)(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference =
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
  )(implicit env: CoinTestConsoleEnvironment): LocalSplitwiseAppReference =
    env.splitwises.local
      .find(_.name == name)
      .getOrElse(sys.error(s"local splitwise [$name] not configured"))

  def rsw(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): RemoteSplitwiseAppReference =
    env.splitwises.remote
      .find(_.name == name)
      .getOrElse(sys.error(s"remote splitwise [$name] not configured"))
}
