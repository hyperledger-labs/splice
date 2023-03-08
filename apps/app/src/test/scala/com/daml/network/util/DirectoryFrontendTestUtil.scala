package com.daml.network.util

import com.daml.network.integration.tests.CoinTests.CoinTestCommon
import com.daml.network.integration.tests.FrontendTestCommon

trait DirectoryFrontendTestUtil extends CoinTestCommon with CnsTestUtil {
  this: CommonCoinAppInstanceReferences & FrontendTestCommon =>

  def allocateDirectoryEntry(
      directoryUiLogin: () => Unit,
      entryName: String,
  )(implicit
      webDriver: WebDriverType
  ) = {
    directoryUiLogin()

    waitForQuery(id("entry-name-field"))

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
