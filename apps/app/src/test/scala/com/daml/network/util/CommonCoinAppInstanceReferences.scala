package com.daml.network.util

import com.daml.network.console.{LocalValidatorAppReference, LocalWalletAppReference}
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment

// TODO(Arne): these should eventually be defined analogue to Canton's `participant1` references etc
// however, likely only possible once Canton is dependent on like a full library
trait CommonCoinAppInstanceReferences {

  def wallet1(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference = w("wallet1")
  def validator1(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference = v(
    "validator1"
  )

  def w(name: String)(implicit env: CoinTestConsoleEnvironment): LocalWalletAppReference =
    env.wallets
      .find(_.name == name)
      .getOrElse(sys.error(s"wallet [$name] not configured"))

  def v(name: String)(implicit env: CoinTestConsoleEnvironment): LocalValidatorAppReference =
    env.validators
      .find(_.name == name)
      .getOrElse(sys.error(s"validator [$name] not configured"))

}
