package com.daml.network.util

import com.daml.network.integration.tests.CoinTests.CoinTestCommon
import com.daml.network.integration.tests.FrontendTestCommon
import org.openqa.selenium.Keys

trait SplitwiseFrontendTestUtil extends CoinTestCommon with CnsTestUtil {
  this: CommonCoinAppInstanceReferences & FrontendTestCommon =>

  def addTeamLunch(quantity: Double)(implicit webDriver: WebDriverType) = {
    inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = quantity.toString
    }
    inside(find(className("enter-payment-description-field"))) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = "Team lunch"
    }
    click on className("enter-payment-link")
  }

  def enterSplitwisePayment(receiver: String, quantity: Double)(implicit
      webDriver: WebDriverType
  ) = {
    inside(find(className("transfer-amount-field"))) { case Some(field) =>
      field.underlying.click()
      reactTextInput(field).value = quantity.toString
    }
    inside(find(className("transfer-receiver-field"))) { case Some(field) =>
      field.underlying.click()
      val input = reactTextInput(field)
      input.underlying.sendKeys(receiver)
      input.underlying.sendKeys(Keys.ARROW_DOWN)
      input.underlying.sendKeys(Keys.ENTER)
    }
    click on className("transfer-link")
  }

}
