package org.lfdecentralizedtrust.splice.integration.tests

import org.openqa.selenium.By

trait VotesFrontendTestUtil { self: FrontendIntegrationTestWithSharedEnvironment =>

  def getAllVoteRows(tableBodyId: String)(implicit webDriver: WebDriverType) = {
    def tableBody = find(id(tableBodyId))
    inside(tableBody) { case Some(tb) =>
      tb.findAllChildElements(className("vote-row-action")).toSeq
    }
  }

  def closeVoteModalsIfOpen(implicit webDriver: WebDriverType) = {
    // if the modal was open due to a previous eventually-call, close it
    scala.util.Try(click on "vote-request-modal-close-button")
    scala.util.Try(click on "vote-result-modal-close-button")
  }

  def parseAmuletConfigValue(key: String, replacement: Boolean = true)(implicit
      webDriver: WebDriverType
  ) = {
    val headElement = webDriver.findElement(By.cssSelector(s"li[data-key='$key']"))
    val value = if (replacement) {
      headElement.findElement(By.cssSelector("div.jsondiffpatch-right-value"))
    } else {
      headElement.findElement(By.cssSelector("div.jsondiffpatch-left-value"))
    }
    value.findElement(By.tagName("pre")).getText.replace("\"", "")
  }

}
