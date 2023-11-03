package com.daml.network.util

import com.daml.network.directory.DirectoryUtil
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.daml.network.integration.tests.FrontendTestCommon
import org.openqa.selenium.support.ui.ExpectedConditions
import org.scalatest.compatible.Assertion

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.duration.*

trait DirectoryFrontendTestUtil extends CNNodeTestCommon with CnsTestUtil {
  this: CommonCNNodeAppInstanceReferences & FrontendTestCommon =>

  private def allocateDirectoryEntry(
      directoryUiLogin: () => Unit,
      entryName: String,
  )(implicit
      webDriver: WebDriverType
  ) = {
    val entryNameWithoutSuffix = entryName.stripSuffix(DirectoryUtil.entryNameSuffix)
    directoryUiLogin()

    // 100 seconds waiting here because we need to wait on the JSON API being ready which is sloooow.
    waitForQuery(id("entry-name-field"), timeUntilSuccess = Some(100.seconds))

    click on "entry-name-field"
    textField("entry-name-field").value = entryNameWithoutSuffix

    waitForCondition(id("search-entry-button")) { ExpectedConditions.elementToBeClickable(_) }
    click on "search-entry-button"

    waitForQuery(id("request-entry-with-sub-button"))
    click on "request-entry-with-sub-button"
  }

  def reserveDirectoryNameFor(
      directoryUiLogin: () => Unit,
      expectedName: String,
      expectedAmount: String,
      expectedCurrency: String,
      expectedInterval: String,
  )(implicit
      webDriver: WebDriverType
  ): Assertion = {

    clue(s"Reserving directory name: ${expectedName}") {
      val timeBeforeAllocate = LocalDateTime.now()
      allocateDirectoryEntry(directoryUiLogin, expectedName)

      // user is redirected to their wallet...
      eventually() {
        findAll(className("sub-request-accept-button")) should have size 1
      }

      click on className("sub-request-accept-button")

      // And then back to directory, where they are already logged in

      // The success page may take a while to show
      val goToDirectoryEntriesButton = eventually() {
        find(id("directory-entries-button")).valueOrFail("The success page did not load.")
      }
      val timeAfterAllocate = LocalDateTime.now()

      click on goToDirectoryEntriesButton

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
        val currency = row.childElement(className("entries-table-currency")).text
        val interval = row.childElement(className("entries-table-payment-interval")).text
        val expiresAt = row.childElement(className("entries-table-expires-at")).text

        name should be(expectedName)
        amount should be(expectedAmount)
        currency should be(expectedCurrency)
        interval should be(expectedInterval)

        val expiryAfter = timeBeforeAllocate.minusMinutes(1).plusDays(90)
        val expiryBefore = timeAfterAllocate.plusMinutes(1).plusDays(90)
        val parsedExpiresAt =
          LocalDateTime.from(DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm").parse(expiresAt))
        parsedExpiresAt should (be >= expiryAfter).and(be <= expiryBefore)
      }
    }
  }
}
