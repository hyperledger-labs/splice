package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, TimeTestUtil, WalletTestUtil}
import org.openqa.selenium.WebDriver

class SvFrontendTimeBasedIntegrationTest
    extends FrontendIntegrationTestWithIsolatedEnvironment("sv1", "sv2")
    with FrontendLoginUtil
    with WalletTestUtil
    with TimeTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4SvsWithSimTime(
        this.getClass.getSimpleName
      )

  def assertRowContentsMatch(key: String, value: String)(implicit webDriver: WebDriver): Unit = {
    val queryResult = find(id(key))
    queryResult should not be empty withClue s"$key ID'd element"
    inside(queryResult) {
      case Some(queryRow) => {
        queryRow.childElement(className("general-dso-key-name")).text should matchText(key)
        seleniumText(
          queryRow.childElement(className("general-dso-value-name"))
        ) should matchText(value)
      }
    }
  }

  def assertRowContentsDiffer(key: String, value: String)(implicit webDriver: WebDriver): Unit = {
    val queryResult = find(id(key))
    queryResult should not be empty withClue s"$key ID'd element"
    inside(queryResult) {
      case Some(queryRow) => {
        queryRow.childElement(className("general-dso-key-name")).text should matchText(
          key
        )
        seleniumText(
          queryRow.childElement(className("general-dso-value-name"))
        ) shouldNot matchText(value)
      }
    }
  }
}
