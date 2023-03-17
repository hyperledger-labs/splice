package com.daml.network.util

import com.daml.network.integration.tests.CoinTests.CoinTestCommon
import com.daml.network.integration.tests.FrontendTestCommon
import scala.concurrent.duration.*

trait DirectoryFrontendTestUtil extends CoinTestCommon with CnsTestUtil {
  this: CommonCoinAppInstanceReferences & FrontendTestCommon =>

  def allocateDirectoryEntry(
      directoryUiLogin: () => Unit,
      entryName: String,
  )(implicit
      webDriver: WebDriverType
  ) = {
    directoryUiLogin()

    // 30 seconds waiting here as in some tests we observed 20 seconds not being enough, due to needing to
    // wait on the JSON API.
    waitForQuery(id("entry-name-field"), timeUntilSuccess = Some(50.seconds))

    click on "entry-name-field"
    textField("entry-name-field").value = entryName

    click on "request-entry-with-sub-button"

    eventually() {
      findAll(className("sub-requests-table-row")) should have size 1
    }
  }

  def reserveDirectoryNameFor(directoryUiLogin: () => Unit, entryName: String)(implicit
      webDriver: WebDriverType
  ): String = {
    clue(s"Reserving directory name: ${entryName}") {
      allocateDirectoryEntry(directoryUiLogin, entryName)

      // user is redirected to their wallet...
      eventually() {
        findAll(className("sub-request-accept-button")) should have size 1
      }
      click on className("sub-request-accept-button")

      // And then back to directory, where they are already logged in
      eventually() {
        findAll(className("entries-table-row")) should have size 1
      }
      val row: Element = inside(findAll(className("entries-table-row")).toList) { case Seq(row) =>
        row
      }
      val name = row.childElement(className("entries-table-name"))
      name.text should be(entryName)
      entryName
    }
  }
}
