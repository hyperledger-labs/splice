package com.daml.network.integration.tests

import com.daml.network.codegen.java.splice.dsorules.VoteRequest
import com.daml.network.console.SvAppBackendReference
import com.daml.network.util.FrontendLoginUtil
import org.openqa.selenium.{By, Keys}

abstract class SvFrontendCommonIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv1", "sv2")
    with FrontendLoginUtil {

  def vote(
      backend: SvAppBackendReference,
      requestId: String,
      isAccept: Boolean,
      numberAccepts: String,
      finalVote: Boolean,
  )(implicit webDriver: WebDriverType) = {
    actAndCheck(
      s"${backend.config.ledgerApiUser} accepts the request",
      backend.castVote(new VoteRequest.ContractId(requestId), isAccept, "", ""),
    )(
      s"the number of accept votes increased to ${numberAccepts}",
      _ => {
        inside(find(id("vote-request-modal-accepted-count"))) {
          case Some(element) => element.text should matchText(numberAccepts)
          case None =>
            finalVote shouldBe true // the vote request might already have been archived at this point
        }
        if (finalVote) {
          webDriver.findElement(By.tagName("body")).sendKeys(Keys.ESCAPE)
        }
      },
    )
  }

  def getParameterNamesFromClass(clazz: Class[_]): List[String] = {
    import java.lang.reflect.Modifier
    val fields = clazz.getDeclaredFields
    val parameterFields = fields.filterNot(f => Modifier.isStatic(f.getModifiers))
    parameterFields.map(_.getName).toList
  }

}
