package com.daml.network.util

import com.daml.network.integration.tests.FrontendTestCommon
import org.scalatest.Assertion

trait WalletNewFrontendTestUtil { self: FrontendTestCommon =>

  protected def tapCoins(tapQuantity: BigDecimal)(implicit webDriver: WebDriverType): Unit = {
    click on "tap-amount-field"
    numberField("tap-amount-field").underlying.sendKeys(tapQuantity.toString)
    click on "tap-button"
  }

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

    def parseAmountText(str: String, currency: String) = {
      try {
        BigDecimal(
          str
            .replace(currency, "")
            .trim
        )
      } catch {
        case e: Throwable =>
          throw new RuntimeException(s"Could not parse the string '$str' as a coin amount", e)
      }
    }

    val ccAmount = parseAmountText(
      transactionRow
        .childElement(className("tx-amount-cc"))
        .text,
      currency = "CC",
    )
    val usdAmount = parseAmountText(
      transactionRow
        .childElement(className("tx-amount-usd"))
        .text,
      currency = "USD",
    )

    ccAmount should beWithin(expectedAmountCC - smallAmount, expectedAmountCC)
    usdAmount should beWithin(
      expectedAmountUSD - smallAmount * coinPrice,
      expectedAmountUSD,
    )

    transactionRow.childElement(className("tx-amount-rate")).text should matchText(
      s"${BigDecimal(1) / coinPrice} CC/USD"
    )
  }

}
