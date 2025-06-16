package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon
import scala.concurrent.duration.DurationInt

trait SvFrontendTestUtil extends TestCommon {
  this: CommonAppInstanceReferences & FrontendTestCommon =>

  def setExpirationDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-expiration", dateTime)
  }

  def setEffectiveDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-effectivity", dateTime)
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
      eventually(5.seconds)(
        find(id("create-voterequest-submit-button")).value.isEnabled shouldBe enabled
      )
    }
    if (enabled) {
      clue("click the submit button") {
        click on "create-voterequest-submit-button"
      }
      clue("click accept on the confirmation dialog") {
        click on "vote-confirmation-dialog-accept-button"
      }
    }
  }
}
