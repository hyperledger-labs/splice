package com.daml.network.util

import com.daml.network.console.{
  LocalDirectoryProviderAppReference,
  LocalDirectoryUserAppReference,
  LocalScanAppReference,
  LocalSplitwiseAppReference,
  LocalSvcAppReference,
  LocalValidatorAppReference,
  LocalWalletAppReference,
  RemoteSvcAppReference,
  RemoteWalletAppReference,
  RemoteSplitwiseAppReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.digitalasset.canton.topology.PartyId

// TODO(Arne): these should eventually be defined analogue to Canton's `participant1` references etc
// however, likely only possible once Canton is dependent on like a full library
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
  def scan(implicit env: CoinTestConsoleEnvironment): LocalScanAppReference = env.scanOpt.getOrElse(
    sys.error("Tried to access the Scan app but it isn't defined in the test's configuration file")
  )
  def aliceWallet(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w(
    "aliceWallet"
  )
  def aliceRemoteWallet(implicit env: CoinTestConsoleEnvironment): RemoteWalletAppReference = rw(
    "aliceRemoteWallet"
  )
  def aliceValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "aliceValidator"
  )
  def bobWallet(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w(
    "bobWallet"
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
  def directoryProvider(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryProviderAppReference = dp(
    "directoryProvider"
  )

  def aliceDirectoryUser(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryUserAppReference = du(
    "aliceDirectoryUser"
  )

  def bobDirectoryUser(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryUserAppReference = du(
    "bobDirectoryUser"
  )

  def aliceSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteSplitwiseAppReference = rsw(
    "aliceSplitwise"
  )

  def aliceSplitwiseBackend(implicit
      env: CoinTestConsoleEnvironment
  ): LocalSplitwiseAppReference = sw(
    "aliceSplitwiseBackend"
  )

  def bobSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteSplitwiseAppReference = rsw(
    "bobSplitwise"
  )

  def bobSplitwiseBackend(implicit
      env: CoinTestConsoleEnvironment
  ): LocalSplitwiseAppReference = sw(
    "bobSplitwiseBackend"
  )

  def providerSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): RemoteSplitwiseAppReference = rsw(
    "providerSplitwise"
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
    env.validators
      .find(_.name == name)
      .getOrElse(sys.error(s"validator [$name] not configured"))

  def dp(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): LocalDirectoryProviderAppReference =
    env.directoryProviders
      .find(_.name == name)
      .getOrElse(sys.error(s"directory provider [$name] not configured"))

  def du(
      name: String
  )(implicit env: CoinTestConsoleEnvironment): LocalDirectoryUserAppReference =
    env.directoryUsers
      .find(_.name == name)
      .getOrElse(sys.error(s"directory user [$name] not configured"))

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
