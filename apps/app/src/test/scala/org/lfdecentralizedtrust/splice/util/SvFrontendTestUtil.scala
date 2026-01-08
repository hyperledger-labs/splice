package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon
import org.openqa.selenium.By
import scala.concurrent.duration.DurationInt

trait SvFrontendTestUtil extends TestCommon {
  this: CommonAppInstanceReferences & FrontendTestCommon =>

  def setExpirationDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-expiration", dateTime)
  }

  def setThresholdDeadline(party: String, dateTime: String, id: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, id, dateTime)
  }

  def setEffectiveDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-effectivity", dateTime)
  }

  def setEffectiveDate2(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "effective-date-field", dateTime)
  }

  def setAmuletConfigDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-amulet-configuration", dateTime)
  }

  def clickVoteRequestSubmitButtonOnceEnabled(
      enabled: Boolean = true
  )(implicit webDriver: WebDriverType) = {
    clue("wait for the submit button to become clickable") {
      eventually()(
        find(id("create-voterequest-submit-button")).value.isEnabled shouldBe enabled
      )
    }
    if (enabled) {
      clue("click the submit button") {
        eventuallyClickOn(id("create-voterequest-submit-button"))
      }
      clue("click accept on the confirmation dialog") {
        eventuallyClickOn(id("vote-confirmation-dialog-accept-button"))
      }
    }
  }

  def clickVoteRequestSubmitButtonOnceEnabled2(
      enabled: Boolean = true
  )(implicit webDriver: WebDriverType) = {

    // ensure all validations on the form are done
    webDriver.findElement(By.tagName("body")).click()

    def submitButton = find(id("submit-button"))

    clue("wait for the review button to become clickable") {
      eventually()(
        submitButton.value.isEnabled shouldBe enabled
      )
    }

    clue("click the review button and progress to review page") {
      inside(find(xpath("//button[contains(text(), 'Review Proposal')]"))) {
        case Some(button) =>
          button.underlying.click()
        case None =>
          fail("Could not find review button")
      }
    }

    clue("Submit form") {
      eventuallySucceeds() {
        val submitButton =
          webDriver.findElement(By.xpath("//button[contains(text(), 'Submit Proposal')]"))
        submitButton.click()
      }
    }
  }
}
