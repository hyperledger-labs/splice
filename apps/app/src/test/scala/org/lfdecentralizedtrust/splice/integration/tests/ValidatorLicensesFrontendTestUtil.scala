package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.topology.PartyId
import org.scalatest.Assertion

trait ValidatorLicensesFrontendTestUtil { self: FrontendIntegrationTestWithSharedEnvironment =>

  def getLicensesTableRows(implicit webDriver: WebDriverType) = {
    findAll(className("validator-licenses-table-row")).toList
  }

  def checkLastValidatorLicenseRow(
      previousSize: Long,
      expectedSponsor: PartyId,
      expectedValidator: PartyId,
  )(implicit webDriver: WebDriverType): Assertion = {
    val newLicenseRows = findAll(className("validator-licenses-table-row")).toList
    newLicenseRows should have size (previousSize + 1L)
    val row: Element = inside(newLicenseRows) { case row :: _ =>
      row
    }
    val sponsor =
      seleniumText(row.childElement(className("validator-licenses-sponsor")))

    val validator =
      seleniumText(row.childElement(className("validator-licenses-validator")))

    sponsor shouldBe expectedSponsor.toProtoPrimitive
    validator shouldBe expectedValidator.toProtoPrimitive
  }

}
