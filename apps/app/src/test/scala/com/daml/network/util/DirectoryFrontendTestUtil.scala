package com.daml.network.util

import com.daml.network.integration.tests.CNNodeTests.CNNodeTestCommon
import com.daml.network.integration.tests.FrontendTestCommon

import scala.concurrent.duration.*
import org.openqa.selenium.support.ui.ExpectedConditions
import org.scalatest.compatible.Assertion

import java.time.format.DateTimeFormatter
import java.time.{Duration, LocalDateTime}
import com.daml.network.directory.DirectoryUtil

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
      click on goToDirectoryEntriesButton

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

        // only compare date and not time to avoid test flakes when running the test right when the hour changes
        // We accept the flake when running this test right at the change of the date.
        val expiry = LocalDateTime.now().plus(Duration.ofDays(90))
        val expectedExpiry =
          DateTimeFormatter
            .ofPattern("MM/dd/yyyy")
            .format(expiry)
        expiresAt should startWith(expectedExpiry)
      }
    }
  }
}
