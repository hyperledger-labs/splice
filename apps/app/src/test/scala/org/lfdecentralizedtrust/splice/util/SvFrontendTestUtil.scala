package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon

trait SvFrontendTestUtil extends TestCommon {
  this: CommonAppInstanceReferences & FrontendTestCommon =>

  def setLegacyExpiryDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-expiration", dateTime)
  }

  def setExpiryDate(party: String, formPrefix: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, s"$formPrefix-expiry-date", dateTime)
  }

  def setLegacyEffectiveDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-effectivity", dateTime)
  }

  def setEffectiveDate(party: String, formPrefix: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, s"$formPrefix-effective-date-field", dateTime)
  }

  def clickLegacyVoteRequestSubmitButtonOnceEnabled(
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

}
