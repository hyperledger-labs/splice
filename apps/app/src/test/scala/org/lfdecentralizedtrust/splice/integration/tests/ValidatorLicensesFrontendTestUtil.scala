package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

trait ValidatorLicensesFrontendTestUtil { self: FrontendIntegrationTestWithSharedEnvironment =>

  def getLicensesTableRows(implicit webDriver: WebDriverType): Seq[Element] = {
    findAll(className("validator-licenses-table-row")).toList
  }

  def checkValidatorLicenseRow(
      previousSize: Long,
      expectedSponsor: PartyId,
      expectedValidator: PartyId,
  )(implicit webDriver: WebDriverType): Assertion = {
    val newLicenseRows = getLicensesTableRows
    newLicenseRows should have size (previousSize + 1L)
    forExactly(1, newLicenseRows) { row =>
      val validator =
        seleniumText(row.childElement(className("validator-licenses-validator")))
      val sponsor =
        seleniumText(row.childElement(className("validator-licenses-sponsor")))
      validator shouldBe expectedValidator.toProtoPrimitive
      sponsor shouldBe expectedSponsor.toProtoPrimitive
    }
  }
}
