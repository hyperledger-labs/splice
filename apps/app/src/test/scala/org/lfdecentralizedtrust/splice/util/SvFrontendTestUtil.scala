package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon

trait SvFrontendTestUtil extends TestCommon {
  this: CommonAppInstanceReferences & FrontendTestCommon =>

  def setExpiryDate(party: String, formPrefix: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    eventuallySucceeds() {
      setDateTime(party, s"$formPrefix-expiry-date-field", dateTime)
    }
  }

  def setEffectiveDate(party: String, formPrefix: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    eventuallySucceeds() {
      setDateTime(party, s"$formPrefix-effective-date-field", dateTime)
    }
  }

}
