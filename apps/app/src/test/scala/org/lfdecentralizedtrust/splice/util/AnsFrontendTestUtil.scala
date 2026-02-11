package org.lfdecentralizedtrust.splice.util

import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.TestCommon
import org.lfdecentralizedtrust.splice.integration.tests.FrontendTestCommon
import org.lfdecentralizedtrust.splice.sv.util.AnsUtil
import org.openqa.selenium.support.ui.ExpectedConditions
import org.scalatest.compatible.Assertion

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*

trait AnsFrontendTestUtil extends TestCommon with AnsTestUtil {
  this: CommonAppInstanceReferences & FrontendTestCommon =>

  private def allocateAnsEntry(
      ansUiLogin: () => Unit,
      entryName: String,
      ansAcronym: String,
  )(implicit
      webDriver: WebDriverType
  ) = {
    val ansUtil = new AnsUtil(ansAcronym)
    val entryNameWithoutSuffix = entryName.stripSuffix(ansUtil.entryNameSuffix)
    ansUiLogin()

    // 100 seconds waiting here because we need to wait on the JSON API being ready which is sloooow.
    waitForQuery(id("entry-name-field"), timeUntilSuccess = Some(100.seconds))

    eventuallyClickOn(id("entry-name-field"))
    textField("entry-name-field").value = entryNameWithoutSuffix

    waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
    eventuallyClickOn(id("search-entry-button"))

    waitForQuery(id("request-entry-with-sub-button"))
    eventuallyClickOn(id("request-entry-with-sub-button"))
  }

  def reserveAnsNameFor(
      ansUiLogin: () => Unit,
      expectedName: String,
      expectedAmount: String,
      expectedUnit: String,
      expectedInterval: String,
      ansAcronym: String,
  )(implicit
      webDriver: WebDriverType
  ): Assertion = {

    clue(s"Reserving ans name: ${expectedName}") {
      val timeBeforeAllocate = LocalDateTime.now()
      allocateAnsEntry(ansUiLogin, expectedName, ansAcronym)

      // user is redirected to their wallet...
      eventually() {
        findAll(className("sub-request-accept-button")) should have size 1
      }

      eventuallyClickOn(className("sub-request-accept-button"))

      // And then back to ans, where they are already logged in

      // The success page may take a while to show
      // Bumping the eventually timeout due to the decentralized ANS would take more time to collect the initial payment.
      val goToAnsEntriesButton = eventually(timeUntilSuccess = 200.seconds) {
        find(id("ans-entries-button")).valueOrFail("The success page did not load.")
      }
      val timeAfterAllocate = LocalDateTime.now()

      click on goToAnsEntriesButton

      // slowness observed in #8421, but we're not bumping the timeout as it could just be the CPU hiccups
      // see: https://github.com/DACH-NY/canton-network-node/pull/8423#issuecomment-1792530571
      eventually() {
        val row = findAll(className("entries-table-row"))
          .find(
            _.childElement(className("entries-table-name")).text == expectedName
          )
          .value

        val name = row.childElement(className("entries-table-name")).text
        val amount = row.childElement(className("entries-table-amount")).text
        val denomination = row.childElement(className("entries-table-currency")).text
        val interval = row.childElement(className("entries-table-payment-interval")).text
        val expiresAt = row.childElement(className("entries-table-expires-at")).text

        name should be(expectedName)
        amount should be(expectedAmount)
        denomination should be(expectedUnit)
        interval should be(expectedInterval)

        val expiryAfter = timeBeforeAllocate.minusMinutes(1).plusDays(90)
        val expiryBefore = timeAfterAllocate.plusMinutes(1).plusDays(90)
        val parsedExpiresAt =
          LocalDateTime.from(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").parse(expiresAt))
        parsedExpiresAt should (be >= expiryAfter).and(be <= expiryBefore)
      }
    }
  }
}
