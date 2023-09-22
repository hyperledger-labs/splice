package com.daml.network.integration.tests

import com.daml.network.codegen.java.cc.api.v1.coin.EnabledChoices
import com.daml.network.codegen.java.cn.svcrules.VoteRequest
import com.daml.network.console.SvAppBackendReference
import com.daml.network.util.FrontendLoginUtil
import org.openqa.selenium.{By, Keys}
import org.openqa.selenium.support.ui.Select

abstract class SvFrontendCommonIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv1", "sv2")
    with FrontendLoginUtil {

  "can create a valid CRARC_SetEnabledChoices vote request" in { implicit env =>
    val requestReasonUrl = "This is a request reason url."
    val requestReasonBody = "This is a request reason."

    val newEnabledChoices = new EnabledChoices(
      false, false, false, false, false, true, false, false,
    )

    withFrontEnd("sv1") { implicit webDriver =>
      actAndCheck(
        "sv1 operator can login and browse to the governance tab", {
          go to s"http://localhost:$sv1UIPort/votes"
          loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
        },
      )(
        "sv1 can see the create vote request button",
        _ => {
          sv1Backend
            .getSvcInfo()
            .coinRules
            .payload
            .enabledChoices should not equal (newEnabledChoices)
        },
      )

      val (_, requestId) = actAndCheck(
        "sv1 operator can create a new vote request with two future schedules on different dates", {
          val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
          dropDownAction.selectByValue("CRARC_SetEnabledChoices")

          val switchGroup = webDriver.findElement(By.id("switch-group-enabled-choices"))
          val switches = switchGroup.findElements(By.id("switch"))

          val EnabledChoicesParameters = getParameterNamesFromClass(classOf[EnabledChoices])
          if (sv1Backend.getSvcInfo().coinRules.payload.isDevNet) {
            switches.size shouldBe EnabledChoicesParameters.size
          } else {
            switches.size shouldBe EnabledChoicesParameters.filter(!_.contains("DevNet")).size
          }

          switches.forEach(s =>
            if (s.isSelected && !s.getAccessibleName.contains("DevNet")) s.click()
          )

          clue("sv1 modifies url") {
            find(id("create-reason-url")).value.underlying.sendKeys(requestReasonUrl)
          }
          clue("sv1 modifies summary") {
            find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
          }

          click on "create-voterequest-submit-button"
        },
      )(
        "sv1 can see the new vote request",
        _ => {
          click on "tab-panel-in-progress"

          val tbody = find(id("sv-voting-in-progress-table-body"))
          val tb = tbody.value
          val rows = tb.findAllChildElements(className("vote-row-action")).toSeq
          rows should have size 1
          rows.head.text shouldBe "CRARC_SetEnabledChoices"

          val reviewButton = rows.head
          reviewButton.underlying.click()

          val requestId =
            inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
              tb.text
            }
          requestId
        },
      )

      actAndCheck(
        "sv2 and sv3 accept the request", {
          vote(sv2Backend, requestId, true, "2", false)
          vote(sv3Backend, requestId, true, "3", true)
        },
      )(
        "the request went through and all choices are disabled",
        _ => {
          sv1Backend.getSvcInfo().coinRules.payload.enabledChoices should equal(newEnabledChoices)
        },
      )

      clue("the vote request is marked as executed") {
        eventually() {
          click on "tab-panel-executed"
          val tbody = find(id("sv-vote-results-executed-table-body"))
          val tb = tbody.value
          val rows = tb.findAllChildElements(className("vote-row-action")).toSeq
          rows.size shouldBe 1
        }
      }
    }
  }

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
