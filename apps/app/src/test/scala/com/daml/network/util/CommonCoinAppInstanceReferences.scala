package com.daml.network.util

import com.daml.network.console.{
  LocalDirectoryProviderAppReference,
  LocalDirectoryUserAppReference,
  LocalScanAppReference,
  LocalSvcAppReference,
  LocalValidatorAppReference,
  LocalWalletAppReference,
  RemoteSvcAppReference,
  RemoteWalletAppReference,
  LocalSplitwiseAppReference,
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
  def aliceValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "aliceValidator"
  )
  def bobWallet(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w(
    "bobWallet"
  )
  def bobValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "bobValidator"
  )
  def directoryValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "directoryValidator"
  )
  def directoryProvider(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryProviderAppReference = dp(
    "directoryProvider"
  )

  def directoryUser(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryUserAppReference = du(
    "directoryUser"
  )

  def aliceSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): LocalSplitwiseAppReference = sw(
    "aliceSplitwise"
  )

  def bobSplitwise(implicit
      env: CoinTestConsoleEnvironment
  ): LocalSplitwiseAppReference = sw(
    "bobSplitwise"
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
    env.splitwises
      .find(_.name == name)
      .getOrElse(sys.error(s"splitwise [$name] not configured"))
}
