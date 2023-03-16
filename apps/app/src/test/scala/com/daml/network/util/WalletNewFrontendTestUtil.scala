package com.daml.network.util

import com.daml.network.integration.tests.FrontendTestCommon
import org.scalatest.Assertion

trait WalletNewFrontendTestUtil { self: FrontendTestCommon =>

  protected def matchBalance(balanceCC: String, balanceUSD: String)(implicit
      webDriverType: WebDriverType
  ): Assertion = {
    find(id("wallet-balance-cc"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceCC CC")

    find(id("wallet-balance-usd"))
      .valueOrFail("Couldn't find balance")
      .text should matchText(s"$balanceUSD USD")
  }

}
