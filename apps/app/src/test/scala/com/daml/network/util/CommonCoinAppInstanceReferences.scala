package com.daml.network.util

import com.daml.network.console.{
  LocalDirectoryProviderAppReference,
  LocalDirectoryUserAppReference,
  LocalScanAppReference,
  LocalSvcAppReference,
  LocalValidatorAppReference,
  LocalWalletAppReference,
}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment

// TODO(Arne): these should eventually be defined analogue to Canton's `participant1` references etc
// however, likely only possible once Canton is dependent on like a full library
trait CommonCoinAppInstanceReferences {

  def svc(implicit env: CoinTestConsoleEnvironment): LocalSvcAppReference = env.svcOpt.getOrElse(
    sys.error("Tried to access the SVC app but it isn't defined in the test's configuration file")
  )
  def scan(implicit env: CoinTestConsoleEnvironment): LocalScanAppReference = env.scanOpt.getOrElse(
    sys.error("Tried to access the Scan app but it isn't defined in the test's configuration file")
  )
  def wallet1(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w("wallet1")
  def validator1(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "validator1"
  )
  def directoryValidator(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "directoryValidator"
  )
  def directoryProvider(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryProviderAppReference = dp(
    "directoryprovider"
  )

  def directoryUser(implicit
      env: CoinTestConsoleEnvironment
  ): LocalDirectoryUserAppReference = du(
    "directoryuser"
  )

  def w(name: String)(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference =
    env.wallets
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
}
