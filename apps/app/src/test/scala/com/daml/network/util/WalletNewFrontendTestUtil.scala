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

  protected def matchTransaction(transactionRow: Element)(
      coinPrice: BigDecimal,
      expectedAction: String,
      expectedParty: Option[String],
      expectedAmountCC: BigDecimal,
  ): Assertion = {
    val expectedAmountUSD = expectedAmountCC * coinPrice
    transactionRow.childElement(className("tx-action")).text should matchText(expectedAction)
    expectedParty.foreach { party =>
      transactionRow.childElement(className("tx-party")).text should matchText(party)
    }

    val ccAmount = transactionRow
      .childElement(className("tx-amount-cc"))
      .text
      .replace("+", "")
      .replace("CC", "")
      .trim
    val usdAmount = transactionRow
      .childElement(className("tx-amount-usd"))
      .text
      .replace("+", "")
      .replace("USD", "")
      .trim

    if (expectedAmountCC > 0) {
      BigDecimal(ccAmount) should beWithin(expectedAmountCC, expectedAmountCC + smallAmount)
      BigDecimal(usdAmount) should beWithin(
        expectedAmountUSD,
        expectedAmountUSD + smallAmount * coinPrice,
      )
    } else {
      BigDecimal(ccAmount) should beWithin(expectedAmountCC - smallAmount, expectedAmountCC)
      BigDecimal(usdAmount) should beWithin(
        expectedAmountUSD - smallAmount * coinPrice,
        expectedAmountUSD,
      )
    }

    transactionRow.childElement(className("tx-amount-rate")).text should matchText("0.5 CC/USD")
  }

}
