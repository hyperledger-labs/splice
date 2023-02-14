package com.daml.network.util

import com.daml.network.integration.tests.FrontendTestCommon

trait WalletFrontendTestUtil { self: FrontendTestCommon =>

  protected def tapAndListCoins(tapQuantity: Int)(implicit webDriver: WebDriverType) = {
    click on "tap-amount-field"
    numberField("tap-amount-field").underlying.sendKeys(s"$tapQuantity.0")
    click on "tap-button"
    eventually() {
      findAll(className("coins-table-row")) should have size 1
    }
  }

  protected def verifyRequestAmountIsDisplayed(amount: Int)(implicit
      webDriver: WebDriverType
  ) = {
    // Verify that the total amount of CC is properly displayed
    eventually() {
      inside(findAll(className("app-requests-table-row")).toList) { case Seq(row) =>
        // Verify that the currency and amount are properly displayed
        row.childElement(className("app-request-total-amount")).text should matchText(
          s"$amount.00000000CC"
        )
      }
    }
  }

  def verifyReceiverTableAmounts(
      expectedAmounts: String*
  )(implicit webDriverType: WebDriverType) = {
    eventually() {
      val amounts =
        findAll(className("receiver-amount-row")).toList.map(row =>
          row.childElement(className("app-request-payment-amount")).text
        )

      amounts should contain theSameElementsAs expectedAmounts
    }
  }

}
