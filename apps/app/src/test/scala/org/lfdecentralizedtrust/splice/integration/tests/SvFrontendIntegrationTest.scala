package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SvFrontendTestUtil,
  SvTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.support.ui.Select
import org.openqa.selenium.{By, Keys}
import org.slf4j.event.Level

import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_AcceptedButActionFailed
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.CloseVoteRequestTrigger

class SvFrontendIntegrationTest
    extends SvFrontendCommonIntegrationTest
    with SvTestUtil
    with SvFrontendTestUtil
    with FrontendLoginUtil
    with WalletTestUtil
    with VotesFrontendTestUtil
    with ValidatorLicensesFrontendTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)

  "SV UIs" should {
    "have basic login functionality" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "login works with correct password", {
            login(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "logged in in the sv ui",
          _ => find(id("app-title")).value.text should matchText("SUPER VALIDATOR OPERATIONS"),
        )
      }
    }

    "warn if user fails to login" in { _ =>
      withFrontEnd("sv1") { implicit webDriver =>
        loggerFactory.assertLogsSeq(SuppressionRule.LevelAndAbove(Level.WARN))(
          {
            actAndCheck(
              "login does not work with wrong user", {
                login(sv1UIPort, "WrongUser")
              },
            )(
              "login fails",
              _ =>
                find(id("loginFailed")).value.text should matchText(
                  "User unauthorized to act as the SV Party."
                ),
            )
          },
          entries => {
            forExactly(1, entries) {
              _.warningMessage should include(
                "Authorization Failed"
              )
            }
            // Vite loads the generated daml code multiple times which triggers this warning.
            // We also ignore that in our warning checker on CI.
            forExactly(entries.length - 1, entries) {
              _.warningMessage should include(
                "Trying to re-register"
              )
            }
          },
        )
      }
    }

    "have 5 information tabs" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "DSO and amulet infos are displayed in pretty json", {
            login(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "We see the 5 tab panels",
          _ => {
            inside(find(id("information-tab-general"))) { case Some(e) =>
              e.text shouldBe "General"
            }
            inside(find(id("information-tab-dso-info"))) { case Some(e) =>
              e.text shouldBe "DSO Info"
            }
            inside(find(id("information-tab-amulet-info"))) { case Some(e) =>
              e.text shouldBe s"$amuletName Info"
            }
            inside(find(id("information-tab-cometBft-debug"))) { case Some(e) =>
              e.text shouldBe "CometBFT Debug Info"
            }
            inside(find(id("information-tab-canton-domain-status"))) { case Some(e) =>
              e.text shouldBe "Domain Node Status"
            }
          },
        )
        actAndCheck("Click on general information tab", click on "information-tab-general")(
          "observe information on party information",
          _ => {
            val valueCells = findAll(className("general-dso-value-name")).toSeq
            valueCells should have length 9
            forExactly(1, valueCells)(cell =>
              seleniumText(cell) should matchText(sv1Backend.config.ledgerApiUser)
            )
            forExactly(3, valueCells)(cell =>
              seleniumText(cell) should matchText(
                sv1Backend.getDsoInfo().svParty.toProtoPrimitive
              )
            )
          },
        )
        actAndCheck(
          "Click on domain status tab",
          click on "information-tab-canton-domain-status",
        )(
          "Observe sequencer and mediator as active",
          _ => {
            val activeCells = findAll(className("active-value")).toSeq
            activeCells should have length 2
            forAll(activeCells)(_.text shouldBe "true")
          },
        )
      }
    }

    "can prepare an onboarding secret for new validator" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        val (_, rowSize) = actAndCheck(
          "sv1 operator can login and browse to the validator onboarding tab", {
            go to s"http://localhost:$sv1UIPort/validator-onboarding"
            loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "We see a button for creating onboarding secret",
          _ => {
            find(className("onboarding-secret-table")) should not be empty
            val rows = findAll(className("onboarding-secret-table-row")).toSeq
            find(id("create-validator-onboarding-secret")) should not be empty
            rows.size
          },
        )

        val (_, newSecret) = actAndCheck(
          "click on the button to create an onboarding secret", {
            click on "create-validator-onboarding-secret"
          },
        )(
          "a new secret row is added",
          _ => {
            val secrets = findAll(
              className("onboarding-secret-table-secret")
            ).toSeq
            secrets should have size (rowSize + 1L)
            secrets.head.text
          },
        )

        val licenseRows = getLicensesTableRows
        val newValidatorParty = allocateRandomSvParty("validatorX")

        actAndCheck(
          "onboard new validator using the secret",
          sv1Backend.onboardValidator(
            newValidatorParty,
            newSecret,
            s"${newValidatorParty.uid.identifier}@example.com",
          ),
        )(
          "a new validator row is added",
          _ => {
            checkLastValidatorLicenseRow(
              licenseRows.size.toLong,
              sv1Backend.getDsoInfo().svParty,
              newValidatorParty,
            )
          },
        )
      }
    }

    "can view median amulet price and update desired amulet price by each SV" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can login and browse to the amulet price tab", {
            go to s"http://localhost:$sv1UIPort/amulet-price"
            loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "We see a median amulet price, desired amulet price of SV1 and other SVs, open mining rounds",
          _ => {
            val shownAmuletPrice = s"${walletAmuletPrice.stripTrailingZeros.toPlainString} USD"
            inside(find(id("median-amulet-price-usd"))) { case Some(e) =>
              e.text shouldBe shownAmuletPrice
            }
            inside(find(id("cur-sv-amulet-price-usd"))) { case Some(e) =>
              e.text shouldBe shownAmuletPrice
            }
            val rows = findAll(className("amulet-price-table-row")).toSeq
            rows should have size 3
            svAmuletPriceShouldMatch(rows, sv2Backend.getDsoInfo().svParty, shownAmuletPrice)
            svAmuletPriceShouldMatch(rows, sv3Backend.getDsoInfo().svParty, "Not Set")
            svAmuletPriceShouldMatch(rows, sv4Backend.getDsoInfo().svParty, "Not Set")

            val roundRows = findAll(className("open-mining-round-row")).toSeq
            roundRows should have size 3
            forEvery(roundRows) {
              _.childElement(className("amulet-price")).text shouldBe shownAmuletPrice
            }
          },
        )

        def showBigDecimal(v: BigDecimal) = v.bigDecimal.stripTrailingZeros.toPlainString

        val testDesiredPriceChange = (desiredPrice: BigDecimal, otherValues: Seq[BigDecimal]) => {
          inside(find(id("median-amulet-price-usd"))) { case Some(e) =>
            e.text shouldBe s"${median(Seq(desiredPrice) ++ otherValues).map(showBigDecimal).getOrElse('0')} USD"
          }
          inside(find(id("cur-sv-amulet-price-usd"))) { case Some(e) =>
            e.text shouldBe s"${showBigDecimal(desiredPrice)} USD"
          }
          val rows = findAll(className("amulet-price-table-row")).toSeq
          rows should have size 3
          forEvery(
            Table(
              ("backend", "other value row"),
              (sv2Backend, 0),
              (sv3Backend, 1),
              (sv4Backend, 2),
            )
          ) { (backend, otherValueRow) =>
            svAmuletPriceShouldMatch(
              rows,
              backend.getDsoInfo().svParty,
              otherValues
                .lift(otherValueRow)
                .fold("Not Set")(v => s"${showBigDecimal(v)} USD"),
            )
          }
        }

        actAndCheck(
          "sv1 operator can change the desired price", {
            click on "edit-amulet-price-button"
            click on "desired-amulet-price-field"
            numberField("desired-amulet-price-field").underlying.clear()
            numberField("desired-amulet-price-field").underlying.sendKeys("10.55")

            click on "update-amulet-price-button"
          },
        )(
          "median fractional amulet price changed and amulet price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10.55, Seq(walletAmuletPrice))
          },
        )

        actAndCheck(
          "sv1 operator can change the desired price", {
            click on "edit-amulet-price-button"
            click on "desired-amulet-price-field"
            numberField("desired-amulet-price-field").underlying.clear()
            numberField("desired-amulet-price-field").underlying.sendKeys("10")

            click on "update-amulet-price-button"
          },
        )(
          "median amulet price changed and amulet price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10, Seq(walletAmuletPrice))
          },
        )

        actAndCheck(
          "sv2 set the desired price", {
            eventuallySucceeds() {
              sv2Backend.updateAmuletPriceVote(BigDecimal(15.55))
            }
          },
        )(
          "median amulet price changed and amulet price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10, Seq(15.55))
          },
        )

        actAndCheck(
          "sv3 set the desired price", {
            eventuallySucceeds() {
              sv3Backend.updateAmuletPriceVote(BigDecimal(5))
            }
          },
        )(
          "median amulet price changed and amulet price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10, Seq(15.55, 5))
          },
        )

        actAndCheck(
          "sv4 set the desired price", {
            eventuallySucceeds() {
              sv4Backend.updateAmuletPriceVote(BigDecimal(9.0))
            }
          },
        )(
          "median amulet price changed and amulet price updated on the row for sv4",
          _ => {
            testDesiredPriceChange(10, Seq(15.55, 5, 9))
          },
        )

        actAndCheck(
          "sv1 update the desired price", {
            eventuallySucceeds() {
              sv1Backend.updateAmuletPriceVote(BigDecimal(1.0))
            }
          },
        )(
          "median amulet price changed",
          _ => {
            testDesiredPriceChange(1, Seq(15.55, 5, 9))
          },
        )
      }
    }

    def testCreateAndVoteDsoRulesAction(action: String)(
        fillUpForm: WebDriverType => Unit
    )(validateRequestedActionInModal: WebDriverType => Unit)(implicit
        env: SpliceTestConsoleEnvironment
    ) = {
      val requestReasonUrl = "This is a request reason url."
      val requestReasonBody = "This is a request reason."
      val (createdVoteRequestAction, createdVoteRequestRequester) = withFrontEnd("sv1") {
        implicit webDriver =>
          val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1UIPort/votes"
              loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
            },
          )(
            "sv1 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )

          val (_, (createdVoteRequestAction, createdVoteRequestRequester)) = actAndCheck(
            "sv1 operator can create a new vote request", {
              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue(action)

              fillUpForm(webDriver)

              inside(find(id("create-reason-url"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonUrl)
              }
              clue("sv1 operator can't click submit before adding a summary") {
                find(id("create-voterequest-submit-button")).value.isEnabled shouldBe false
              }
              inside(find(id("create-reason-summary"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }

              setExpirationDate("sv1", "07/12/2034 12:12 AM")

              clickVoteRequestSubmitButtonOnceEnabled()
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              click on "tab-panel-in-progress"

              val tbody = find(id("sv-voting-in-progress-table-body"))
              inside(tbody) { case Some(tb) =>
                val rows = getAllVoteRows("sv-voting-in-progress-table-body")
                rows.size shouldBe previousVoteRequestsInProgress + 1
                (
                  rows.head.text,
                  tb.findAllChildElements(className("vote-row-requester")).toSeq.head.text,
                )
              }
            },
          )
          (createdVoteRequestAction, createdVoteRequestRequester)
      }

      withFrontEnd("sv2") { implicit webDriver =>
        val (_, reviewButton) = actAndCheck(
          "sv2 operator can login and browse to the governance tab", {
            go to s"http://localhost:$sv2UIPort/votes"
            loginOnCurrentPage(sv2UIPort, sv2Backend.config.ledgerApiUser)
          },
        )(
          "sv2 can see the new vote request",
          _ => {
            click on "tab-panel-action-needed"

            val tbody = find(id("sv-voting-action-needed-table-body"))
            inside(tbody) { case Some(tb) =>
              val rows = getAllVoteRows("sv-voting-action-needed-table-body")

              rows.head.text should matchText(
                createdVoteRequestAction
              )
              tb.findAllChildElements(className("vote-row-requester"))
                .toSeq
                .head
                .text should matchText(
                createdVoteRequestRequester
              )
              rows.head.underlying
            }
          },
        )

        actAndCheck(
          "sv2 operator can review the vote request", {
            reviewButton.click()
          },
        )(
          "sv2 can see the new vote request detail",
          _ => {
            inside(find(id("vote-request-modal-action-type"))) { case Some(element) =>
              element.text should matchText("ARC_DsoRules")
            }
            inside(find(id("vote-request-modal-action-name"))) { case Some(element) =>
              element.text should matchText(action)
            }
            validateRequestedActionInModal(webDriver)
            inside(find(id("vote-request-modal-requested-by"))) { case Some(element) =>
              seleniumText(element) should matchText(
                getSvName(1)
              )
            }
            inside(find(id("vote-request-modal-reason-body"))) { case Some(element) =>
              element.text should matchText(requestReasonBody)
            }
            inside(find(id("vote-request-modal-reason-url"))) { case Some(element) =>
              element.text should matchText(requestReasonUrl)
            }
            inside(find(id("vote-request-modal-rejected-count"))) { case Some(element) =>
              element.text should matchText("0")
            }
            inside(find(id("vote-request-modal-accepted-count"))) { case Some(element) =>
              element.text should matchText("1")
            }
          },
        )

        val voteReasonBody = "vote reason body"
        val voteReasonUrl = "vote reason url"
        actAndCheck(
          "sv2 operator can cast vote", {
            click on "cast-vote-button"
            click on "reject-vote-button"
            inside(find(id("vote-reason-url"))) { case Some(element) =>
              element.underlying.sendKeys(voteReasonUrl)
            }
            inside(find(id("vote-reason-body"))) { case Some(element) =>
              element.underlying.sendKeys(voteReasonBody)
            }
            click on "save-vote-button"
          },
        )(
          "sv2 can see the new vote request detail",
          _ => {
            inside(find(id("vote-request-modal-vote-reason-body"))) { case Some(element) =>
              element.text should matchText(voteReasonBody)
            }
            inside(find(id("vote-request-modal-vote-reason-url"))) { case Some(element) =>
              element.text should matchText(voteReasonUrl)
            }
            inside(find(id("vote-request-modal-rejected-count"))) { case Some(element) =>
              element.text should matchText("1")
            }
            inside(find(id("vote-request-modal-accepted-count"))) { case Some(element) =>
              element.text should matchText("1")
            }
          },
        )
      }

      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can see the vote request detail by clicking review button", {
            val rows = getAllVoteRows("sv-voting-in-progress-table-body")
            val reviewButton = rows.head
            reviewButton.underlying.click()
          },
        )(
          "sv1 can see the new vote request detail",
          _ => {
            inside(find(id("vote-request-modal-rejected-count"))) { case Some(element) =>
              element.text should matchText("1")
            }
            inside(find(id("vote-request-modal-accepted-count"))) { case Some(element) =>
              element.text should matchText("1")
            }
          },
        )
      }

      val updatedVoteReasonBody = "new vote reason body"
      val updatedVoteReasonUrl = "new vote reason url"
      withFrontEnd("sv2") { implicit webDriver =>
        actAndCheck(
          "sv2 operator can update its vote", {
            click on "edit-vote-button"
            click on "accept-vote-button"
            inside(find(id("vote-reason-url"))) { case Some(element) =>
              element.underlying.clear()
              element.underlying.sendKeys(updatedVoteReasonUrl)
            }
            inside(find(id("vote-reason-body"))) { case Some(element) =>
              element.underlying.clear()
              element.underlying.sendKeys(updatedVoteReasonBody)
            }
            click on "save-vote-button"
          },
        )(
          "sv2 can see the new updated vote",
          _ => {
            inside(find(id("vote-request-modal-vote-reason-body"))) { case Some(element) =>
              element.text should matchText(updatedVoteReasonBody)
            }
            inside(find(id("vote-request-modal-vote-reason-url"))) { case Some(element) =>
              element.text should matchText(updatedVoteReasonUrl)
            }
            inside(find(id("vote-request-modal-rejected-count"))) { case Some(element) =>
              element.text should matchText("0")
            }
            inside(find(id("vote-request-modal-accepted-count"))) { case Some(element) =>
              element.text should matchText("2")
            }
          },
        )
      }

      withFrontEnd("sv1") { implicit webDriver =>
        clue("sv1 can see the updated vote by sv2") {
          eventually() {
            inside(find(id("vote-request-modal-rejected-count"))) { case Some(element) =>
              element.text should matchText("0")
            }
            inside(find(id("vote-request-modal-accepted-count"))) { case Some(element) =>
              element.text should matchText("2")
            }
          }
        }
      }
    }

    "can create a valid SRARC_OffboardSv vote request and cast vote on it" in { implicit env =>
      val sv3PartyId = sv3Backend.getDsoInfo().svParty.toProtoPrimitive
      testCreateAndVoteDsoRulesAction("SRARC_OffboardSv") { webDriver =>
        val dropDownMember = new Select(webDriver.findElement(By.id("display-members")))
        dropDownMember.selectByValue(sv3PartyId)
      } { implicit webDriver =>
        find(id("srarc_offboardsv-member"))
          .flatMap(_.findChildElement(tagName("input")))
          .flatMap(_.attribute("value")) should be(Some(sv3PartyId))
      }
    }

    "can create a valid SRARC_UpdateSvRewardWeight vote request and cast vote on it" in {
      implicit env =>
        val newWeight = "1234"
        val sv3PartyId = sv3Backend.getDsoInfo().svParty.toProtoPrimitive
        testCreateAndVoteDsoRulesAction("SRARC_UpdateSvRewardWeight") { webDriver =>
          val dropDownMember = new Select(webDriver.findElement(By.id("display-members")))
          dropDownMember.selectByValue(sv3PartyId)

          val weightInput = webDriver.findElement(By.id("reward-weight"))

          weightInput.clear()
          weightInput.sendKeys(newWeight)
        } { implicit webDriver =>
          find(id("srarc_updatesvrewardweight-member"))
            .flatMap(_.findChildElement(tagName("input")))
            .flatMap(_.attribute("value")) should be(Some(sv3PartyId))
          find(id("srarc_updatesvrewardweight-weight")).map(_.text) should be(Some(newWeight))
        }
    }

    "can create valid SRARC_GrantFeaturedAppRight and SRARC_RevokeFeaturedAppRight vote requests" in {
      implicit env =>
        val requestProviderParty = "TestProviderParty"
        val requestReasonUrl = "This is a request reason url."
        val requestReasonBody = "This is a request reason."

        withFrontEnd("sv1") { implicit webDriver =>
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1UIPort/votes"
              loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
            },
          )(
            "sv1 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )

          click on "tab-panel-in-progress"
          val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()

          actAndCheck(
            "sv1 operator can create a new vote request", {
              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue("SRARC_GrantFeaturedAppRight")

              inside(find(id("set-application-provider"))) { case Some(element) =>
                element.underlying.sendKeys(requestProviderParty)
              }
              inside(find(id("create-reason-url"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonUrl)
              }
              clue("sv1 operator can't click submit before adding a summary") {
                find(id("create-voterequest-submit-button")).value.isEnabled shouldBe false
              }
              inside(find(id("create-reason-summary"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }

              clickVoteRequestSubmitButtonOnceEnabled()
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              click on "tab-panel-in-progress"

              val tbody = find(id("sv-voting-in-progress-table-body"))
              inside(tbody) { case Some(tb) =>
                val rows = getAllVoteRows("sv-voting-in-progress-table-body")
                rows.size shouldBe previousVoteRequestsInProgress + 1
                (
                  rows.head.text,
                  tb.findAllChildElements(className("vote-row-requester")).toSeq.head.text,
                )
              }
            },
          )

          val (_, rightCid) = actAndCheck(
            "sv1 operator can see the vote request detail by clicking review button", {
              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              val reviewButton = rows.head
              reviewButton.underlying.click()
            },
          )(
            "sv1 can see the new vote request detail",
            _ => {
              val RightCid =
                inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
                  tb.text
                }
              RightCid
            },
          )

          actAndCheck(
            "sv1 operator can create a new vote request to revoke the featured app right", {
              go to s"http://localhost:$sv1UIPort/votes"

              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue("SRARC_RevokeFeaturedAppRight")

              inside(find(id("set-application-rightcid"))) { case Some(element) =>
                element.underlying.sendKeys(rightCid)
              }

              inside(find(id("create-reason-url"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonUrl)
              }
              clue("sv1 operator can't click submit before adding a summary") {
                find(id("create-voterequest-submit-button")).value.isEnabled shouldBe false
              }
              inside(find(id("create-reason-summary"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }

              clickVoteRequestSubmitButtonOnceEnabled()
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              click on "tab-panel-in-progress"

              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              rows.size shouldBe previousVoteRequestsInProgress + 2
            },
          )
        }
    }

    "SV1 can create valid SRARC_SetConfig (new DsoRules Configuration) vote requests that can expire and get rejected by other SVs" in {
      implicit env =>
        val requestReasonUrl = "This is a request reason url."
        val requestReasonBody = "This is a request reason."

        withFrontEnd("sv1") { implicit webDriver =>
          val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()

          // If we try to create two vote requests for identical configs,
          // the second request will be rejected with "This vote request has already been created."
          def submitSetConfigRequest(
              requestNewNumUnclaimedRewardsThreshold: String,
              expiresSoon: Boolean,
          ) = {
            // The `eventually` guards against `StaleElementReferenceException`s
            // eventually() must contain clickVoteRequestSubmitButtonOnceEnabled() to retry the whole process
            eventually() {
              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue("SRARC_SetConfig")

              inside(find(id("numUnclaimedRewardsThreshold-value"))) { case Some(element) =>
                element.underlying.sendKeys(requestNewNumUnclaimedRewardsThreshold)
              }
              inside(find(id("create-reason-summary"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }
              inside(find(id("create-reason-url"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonUrl)
              }
              if (expiresSoon) {
                setExpirationDate(
                  "sv1",
                  DateTimeFormatter
                    .ofPattern("MM/dd/yyyy hh:mm a")
                    .format(
                      LocalDateTime
                        .ofInstant(CantonTimestamp.now().toInstant, ZoneOffset.UTC)
                        // Might not be wise to go lower than that because the date input doesn't do seconds
                        .plusMinutes(1)
                    ),
                )
              }
              clickVoteRequestSubmitButtonOnceEnabled()
            }
          }

          def checkNewVoteRequestInProgressTab(previousVoteRequestsInProgress: Int) = {
            click on "tab-panel-in-progress"
            val rows = getAllVoteRows("sv-voting-in-progress-table-body")
            rows.size shouldBe previousVoteRequestsInProgress + 1
            rows.head
          }

          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1UIPort/votes"
              loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
            },
          )(
            "sv1 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )

          clue("Pausing vote request expiration automation") {
            sv1Backend.dsoDelegateBasedAutomation
              .trigger[CloseVoteRequestTrigger]
              .pause()
              .futureValue
          }

          actAndCheck(
            "sv1 operator creates a new vote request with a short expiration time", {
              submitSetConfigRequest("41", expiresSoon = true)
            },
          )(
            "sv1 can see the new vote request in the progress tab",
            _ => checkNewVoteRequestInProgressTab(previousVoteRequestsInProgress),
          )

          val (_, requestId) = actAndCheck(
            "sv1 operator creates a new vote request with a long expiration time", {
              submitSetConfigRequest("42", expiresSoon = false)
            },
          )(
            "sv1 can see the new vote request in the progress tab",
            _ => {
              val reviewButton =
                checkNewVoteRequestInProgressTab(previousVoteRequestsInProgress + 1)
              reviewButton.underlying.click()
              val requestId =
                inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
                  tb.text
                }
              requestId
            },
          )

          clue("Resuming vote request expiration automation") {
            sv1Backend.dsoDelegateBasedAutomation.trigger[CloseVoteRequestTrigger].resume()
          }

          clue("Voting to reject the other vote request") {
            vote(sv2Backend, requestId, false, "1", false)
            vote(sv3Backend, requestId, false, "1", false)
            vote(sv4Backend, requestId, false, "1", true)
          }

          clue("the vote requests get rejected (one by vote, one by expiry)") {
            // Generous buffer for expiry
            eventually(120.seconds) {
              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              rows.size shouldBe previousVoteRequestsInProgress
            }
            eventually() {
              find(id("vote-request-modal-root")) shouldBe empty
            }
            click on "tab-panel-rejected"
            eventually() {
              val rows = getAllVoteRows("sv-vote-results-rejected-table-body")
              rows.size shouldBe 2
            }
          }
        }
    }

    "can create a CRARC_AddFutureAmuletConfigSchedule vote request with only a proposal text and no change" in {
      implicit env =>
        val proposalSummary = "This is a request reason, and everything this is about."

        withFrontEnd("sv1") { implicit webDriver =>
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1UIPort/votes"
              loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
            },
          )(
            "sv1 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )

          click on "tab-panel-in-progress"
          val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()

          actAndCheck(
            "sv1 operator can create a request to add a new amulet config schedule", {
              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")
              val effectiveDateTimePicker =
                webDriver.findElement(By.id("datetime-picker-amulet-configuration"))

              clue(s"sv1 selects an effective date") {
                eventually() {
                  effectiveDateTimePicker.clear()
                  effectiveDateTimePicker.click()
                  effectiveDateTimePicker.sendKeys("01/04/2032 08:00 AM")
                  effectiveDateTimePicker.sendKeys(Keys.RETURN)
                }
              }
              setExpirationDate("sv1", "01/04/2032 02:00 AM")

              clue("sv1 operator can't click submit before adding a summary") {
                find(id("create-voterequest-submit-button")).value.isEnabled shouldBe false
              }
              clue("sv1 modifies the summary") {
                find(id("create-reason-summary")).value.underlying.sendKeys(proposalSummary)
              }

              clue("sv1 creates the vote request") {
                clickVoteRequestSubmitButtonOnceEnabled()
              }
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              click on "tab-panel-in-progress"

              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              rows.size shouldBe previousVoteRequestsInProgress + 1
              rows.head.text shouldBe "CRARC_AddFutureAmuletConfigSchedule"

              val reviewButton = rows.head
              reviewButton.underlying.click()

              inside(find(id("vote-request-modal-reason-body"))) { case Some(tb) =>
                tb.text shouldBe proposalSummary
              }
              click on "vote-request-modal-close-button"
            },
          )
        }
    }

    "can create a CRARC_AddFutureAmuletConfigSchedule vote request, update it via a CRARC_UpdateFutureAmuletConfigSchedule vote request," +
      " and remove it via a CRARC_RemoveFutureAmuletConfigSchedule vote request." in {
        implicit env =>
          /* The following aspects are tested here:
           * - the creation of Add/Update/Remove FutureAmuletConfigSchedule
           * - that the accepted Add vote requests is well displayed in Planned section
           * - the alerting if such requests are touching the same schedule time
           * - the vote request expiresAt < effectiveAt
           * - tests if the modal closes when the last voter votes
           * */
          val requestNewTransferConfigFeeValue = "42"
          val optValidatorFaucetValue = "420"
          val requestReasonUrl = "This is a request reason url."
          val requestReasonBody = "This is a request reason."

          withFrontEnd("sv1") { implicit webDriver =>
            actAndCheck(
              "sv1 operator can login and browse to the governance tab", {
                go to s"http://localhost:$sv1UIPort/votes"
                loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
              },
            )(
              "sv1 can see the create vote request button",
              _ => {
                find(id("create-voterequest-submit-button")) should not be empty
              },
            )

            click on "tab-panel-in-progress"
            val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()

            val (_, requestIdAdd) = actAndCheck(
              "sv1 operator can create a request to add a new amulet config schedule", {
                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

                setAmuletConfigDate("sv1", "07/12/2032 12:12 AM")

                clue("sv1 modifies one value") {
                  val input = find(id("transferConfig.createFee.fee-value")).value.underlying
                  input.clear()
                  input.sendKeys(requestNewTransferConfigFeeValue)
                }

                clue("sv1 modifies one optional value") {
                  val input = find(
                    id("issuanceCurve.futureValues.0._2.optValidatorFaucetCap-value")
                  ).value.underlying
                  input.clear()
                  input.sendKeys(optValidatorFaucetValue)
                }

                clue("sv1 modifies another optional value") {
                  val input = find(
                    id("issuanceCurve.initialValue.optValidatorFaucetCap-value")
                  ).value.underlying
                  input.clear()
                  input.sendKeys(optValidatorFaucetValue)
                }

                clue("sv1 reconsiders and sets the value back to null") {
                  find(
                    id("issuanceCurve.initialValue.optValidatorFaucetCap-value")
                  ).value.underlying
                    .clear()
                }

                clue("sv1 modifies the url") {
                  find(id("create-reason-url")).value.underlying.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "07/11/2032 12:12 AM")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the new vote request",
              _ => {
                closeVoteModalsIfOpen
                click on "tab-panel-in-progress"

                val rows = getAllVoteRows("sv-voting-in-progress-table-body")
                rows.size shouldBe previousVoteRequestsInProgress + 1
                rows.head.text shouldBe "CRARC_AddFutureAmuletConfigSchedule"

                val reviewButton = rows.head
                reviewButton.underlying.click()

                val requestId =
                  inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
                    tb.text
                  }

                BigDecimal(parseAmuletConfigValue("createFee")) should be(
                  BigDecimal(requestNewTransferConfigFeeValue)
                )
                BigDecimal(parseAmuletConfigValue("optValidatorFaucetCap", false)) should be(
                  BigDecimal("2.85")
                )
                BigDecimal(parseAmuletConfigValue("optValidatorFaucetCap")) should be(
                  BigDecimal(optValidatorFaucetValue)
                )

                requestId
              },
            )

            vote(sv2Backend, requestIdAdd, true, "2", true)

            actAndCheck(
              "sv1 operator fails to create a vote request at the same datetime", {
                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

                setAmuletConfigDate("sv1", "07/12/2032 12:12 AM")

                clue("sv1 modifies one value") {
                  find(id("transferConfig.createFee.fee-value")).value.underlying
                    .sendKeys(requestNewTransferConfigFeeValue)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "07/11/2032 12:12 AM")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("alerting-datetime-mismatch"))) { case Some(tb) =>
                  tb.text should include("Another vote request for a schedule adjustment")
                }
              },
            )

            vote(sv3Backend, requestIdAdd, true, "3", true)
            vote(sv4Backend, requestIdAdd, true, "4", true)

            clue("the vote request is marked as planned") {
              eventually() {
                click on "tab-panel-planned"
                val rows = getAllVoteRows("sv-vote-results-planned-table-body")
                rows.size shouldBe 1
              }
            }

            actAndCheck(
              "sv1 operator fails to create a vote request because the expiration date is after the effective date.", {
                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

                setAmuletConfigDate("sv1", "07/12/2033 12:12 AM")

                clue("sv1 modifies one value") {
                  find(id("transferConfig.createFee.fee-value")).value.underlying
                    .sendKeys(requestNewTransferConfigFeeValue)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "07/12/2034 12:12 AM")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("alerting-datetime-mismatch"))) { case Some(tb) =>
                  tb.text should include("The expiration date must be before the effective date")
                }
              },
            )

            eventually() {
              click on "tab-panel-in-progress"

              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              rows.size shouldBe previousVoteRequestsInProgress
              sv1ScanBackend
                .getAmuletRules()
                .contract
                .payload
                .configSchedule
                .futureValues should not be empty
            }

            val (_, requestIdUpdate) = actAndCheck(
              "sv1 operator can create a request to update a amulet config schedule", {

                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_UpdateFutureAmuletConfigSchedule")

                val dropDownAmuletConfigDate =
                  new Select(webDriver.findElement(By.id("dropdown-display-schedules-datetime")))
                dropDownAmuletConfigDate.selectByIndex(1)

                clue("sv1 modifies one value") {
                  find(id("transferConfig.createFee.fee-value")).value.underlying
                    .sendKeys(requestNewTransferConfigFeeValue)
                }

                clue("sv1 modifies the url") {
                  find(id("create-reason-url")).value.underlying.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "07/11/2030 12:12 AM")

                clickVoteRequestSubmitButtonOnceEnabled()
              },
            )(
              "sv1 can see the new vote request",
              _ => {
                click on "tab-panel-in-progress"

                val rows = getAllVoteRows("sv-voting-in-progress-table-body")
                rows.size shouldBe previousVoteRequestsInProgress + 1
                rows.head.text shouldBe "CRARC_UpdateFutureAmuletConfigSchedule"

                val reviewButton = rows.head
                reviewButton.underlying.click()

                val requestId =
                  inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
                    tb.text
                  }
                requestId
              },
            )

            vote(sv2Backend, requestIdUpdate, true, "2", true)

            actAndCheck(
              "sv1 operator fails to create a request to update a amulet config scheduled at the same time", {

                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_UpdateFutureAmuletConfigSchedule")

                val dropDownAmuletConfigDate =
                  new Select(webDriver.findElement(By.id("dropdown-display-schedules-datetime")))
                dropDownAmuletConfigDate.selectByIndex(1)

                clue("sv1 modifies one value") {
                  find(id("transferConfig.createFee.fee-value")).value.underlying
                    .sendKeys(requestNewTransferConfigFeeValue)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "07/11/2030 12:12 AM")

                clickVoteRequestSubmitButtonOnceEnabled()
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("alerting-datetime-mismatch"))) { case Some(tb) =>
                  tb.text should include("Another vote request for a schedule adjustment")
                }
              },
            )

            vote(sv3Backend, requestIdUpdate, true, "3", true)
            vote(sv4Backend, requestIdUpdate, true, "4", true)

            eventually() {
              click on "tab-panel-in-progress"

              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              rows.size shouldBe previousVoteRequestsInProgress
              sv1ScanBackend
                .getAmuletRules()
                .contract
                .payload
                .configSchedule
                .futureValues should not be empty
            }

            val (_, requestIdRemove) = actAndCheck(
              "sv1 operator can create a request to remove a amulet config schedule", {
                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_RemoveFutureAmuletConfigSchedule")

                val dropDownAmuletConfigDate =
                  new Select(webDriver.findElement(By.id("dropdown-display-schedules-datetime")))
                dropDownAmuletConfigDate.selectByIndex(1)

                clue("sv1 modifies the url") {
                  find(id("create-reason-url")).value.underlying.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "07/11/2030 12:12 AM")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the new vote request",
              _ => {
                click on "tab-panel-in-progress"

                val rows = getAllVoteRows("sv-voting-in-progress-table-body")
                rows.size shouldBe previousVoteRequestsInProgress + 1
                rows.head.text shouldBe "CRARC_RemoveFutureAmuletConfigSchedule"

                val reviewButton = rows.head
                reviewButton.underlying.click()

                val requestId =
                  inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
                    tb.text
                  }
                requestId
              },
            )

            vote(sv3Backend, requestIdRemove, true, "2", false)
            vote(sv4Backend, requestIdRemove, true, "3", true)

            actAndCheck(
              "sv1 operator fails create a request to remove a amulet config scheduled at the same time", {
                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_RemoveFutureAmuletConfigSchedule")

                val dropDownAmuletConfigDate =
                  new Select(webDriver.findElement(By.id("dropdown-display-schedules-datetime")))
                dropDownAmuletConfigDate.selectByIndex(1)

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "07/11/2030 12:12 AM")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("alerting-datetime-mismatch"))) { case Some(tb) =>
                  tb.text should include("Another vote request for a schedule adjustment")
                }
              },
            )

          }

          withFrontEnd("sv2") { implicit webDriver =>
            actAndCheck(
              "sv2 operator login and browse to the governance tab", {
                go to s"http://localhost:$sv2UIPort/votes"
                loginOnCurrentPage(sv2UIPort, sv2Backend.config.ledgerApiUser)
              },
            )(
              "sv2 sees the new vote request",
              _ => {
                click on "tab-panel-action-needed"

                val rows = getAllVoteRows("sv-voting-action-needed-table-body")
                val reviewButton = rows.head
                reviewButton.underlying.click()
              },
            )

            actAndCheck(
              "sv2 operator accepts the requests", {
                click on "cast-vote-button"
                click on "accept-vote-button"
                click on "save-vote-button"
              },
            )(
              "sv2's modal closes as all SVs voted before the expiration date",
              _ => {
                find(id("accept-vote-button")) shouldBe empty
              },
            )
          }
      }

    "can request DSO delegate election" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can login and browse to the delegate election tab", {
            go to s"http://localhost:$sv1UIPort/delegate"
            loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "We see a button for requesting a delegate election",
          _ => {
            find(id("submit-ranking-delegate-election")) should not be empty
          },
        )

        val svs: Vector[String] =
          sv3Backend.getDsoInfo().dsoRules.payload.svs.keySet().asScala.toVector

        val newLeader = svs.head

        sv2Backend.createElectionRequest(sv2Backend.getDsoInfo().svParty.toProtoPrimitive, svs)
        sv3Backend.createElectionRequest(sv3Backend.getDsoInfo().svParty.toProtoPrimitive, svs)

        loggerFactory.assertEventuallyLogsSeq(SuppressionRule.LevelAndAbove(Level.INFO))(
          actAndCheck(
            "sv1 operator makes his own ranking for his delegate preference", {
              click on "submit-ranking-delegate-election"
            },
          )(
            "The epoch advances by one and the delegate name is changed.",
            _ => {
              find(id("delegate-election-epoch")).value.text should include(
                sv1Backend.getDsoInfo().dsoRules.payload.epoch.toString
              )
              find(id("delegate-election-current-delegate")).value.text should include(newLeader)
            },
          ),
          entries => {
            forExactly(4, entries) { line =>
              line.message should include(
                "Noticed an DsoRules epoch change"
              )
            }
          },
        )

      }
    }

    "if two AddFutureAmuletConfigSchedule actions scheduled at the same time are created concurrently, then only one succeeds" in {
      implicit env =>
        val requestReasonUrl = "This is a request reason url."
        val requestReasonBody = "This is a request reason."

        withFrontEnd("sv1") { implicit webDriver =>
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1UIPort/votes"
              loginOnCurrentPage(sv1UIPort, sv1Backend.config.ledgerApiUser)
            },
          )(
            "sv1 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )
          eventually() {
            val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
            dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

            setAmuletConfigDate("sv1", "07/12/2030 12:12 AM")

            clue("sv1 modifies one value") {
              find(id("transferConfig.createFee.fee-value")).value.underlying
                .sendKeys("4444")
            }

            clue("sv1 modifies the url") {
              find(id("create-reason-url")).value.underlying.sendKeys(requestReasonUrl)
            }
            clue("sv1 modifies the summary") {
              find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
            }
            setExpirationDate("sv1", "07/11/2030 12:12 AM")
          }
        }

        withFrontEnd("sv2") { implicit webDriver =>
          actAndCheck(
            "sv2 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv2UIPort/votes"
              loginOnCurrentPage(sv2UIPort, sv2Backend.config.ledgerApiUser)
            },
          )(
            "sv2 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )
          eventually() {
            val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
            dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

            setAmuletConfigDate("sv2", "07/12/2030 12:12 AM")

            clue("sv2 modifies one value") {
              find(id("transferConfig.createFee.fee-value")).value.underlying
                .sendKeys("5555")
            }

            clue("sv2 modifies the url") {
              find(id("create-reason-url")).value.underlying.sendKeys(requestReasonUrl)
            }
            clue("sv2 modifies the summary") {
              find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
            }
            setExpirationDate("sv2", "07/11/2030 12:12 AM")
          }
        }

        withFrontEnd("sv1") { implicit webDriver =>
          click on "tab-panel-in-progress"
          val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()
          val (_, requestIdAdd1) = actAndCheck(
            "sv1 creates the vote request",
            clickVoteRequestSubmitButtonOnceEnabled(),
          )(
            "sv1 can see the new vote request",
            _ => {
              click on "tab-panel-in-progress"
              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              rows.size shouldBe previousVoteRequestsInProgress + 1
              rows.head.text shouldBe "CRARC_AddFutureAmuletConfigSchedule"

              val reviewButton = rows.head
              reviewButton.underlying.click()

              val requestId =
                inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
                  tb.text
                }
              requestId
            },
          )
          vote(sv2Backend, requestIdAdd1, true, "2", true)
          vote(sv3Backend, requestIdAdd1, true, "3", true)
          vote(sv4Backend, requestIdAdd1, true, "4", true)
        }

        withFrontEnd("sv2") { implicit webDriver =>
          click on "tab-panel-in-progress"
          val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()
          val (_, requestIdAdd2) = actAndCheck(
            "sv2 creates the vote request",
            clickVoteRequestSubmitButtonOnceEnabled(),
          )(
            "sv2 can see the new vote request",
            _ => {
              click on "tab-panel-in-progress"

              val rows = getAllVoteRows("sv-voting-in-progress-table-body")
              rows.size shouldBe previousVoteRequestsInProgress + 1
              rows.head.text shouldBe "CRARC_AddFutureAmuletConfigSchedule"

              val reviewButton = rows.head
              reviewButton.underlying.click()

              val requestId =
                inside(find(id("vote-request-modal-content-contract-id"))) { case Some(tb) =>
                  tb.text
                }
              requestId
            },
          )
          vote(sv1Backend, requestIdAdd2, true, "2", true)
          vote(sv3Backend, requestIdAdd2, true, "3", true)
          loggerFactory.assertLogsSeq(SuppressionRule.Level(Level.WARN))(
            {
              vote(sv4Backend, requestIdAdd2, true, "4", true)

              clue("The last action was accepted but failed") {
                eventually() {
                  sv1Backend
                    .listVoteRequestResults(
                      None,
                      Some(false),
                      Some(getSvName(2)),
                      None,
                      None,
                      1,
                    )
                    .loneElement
                    .outcome shouldBe a[VRO_AcceptedButActionFailed]
                }
              }
            },
            lines => {
              forAtLeast(1, lines) { line =>
                line.message should include(s"was accepted but failed with outcome")
              }
            },
          )
        }
    }
  }

  def setExpirationDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-vote-request-expiration", dateTime)
  }

  def setAmuletConfigDate(party: String, dateTime: String)(implicit
      webDriver: WebDriverType
  ) = {
    setDateTime(party, "datetime-picker-amulet-configuration", dateTime)
  }

  def getVoteRequestsInProgressSize()(implicit webDriver: WebDriverType) = {
    val tbodyInProgress = find(id("sv-voting-in-progress-table-body"))
    tbodyInProgress
      .map(_.findAllChildElements(className("vote-row-action")).toSeq.size)
      .getOrElse(0)
  }

  def getVoteRequestsActionNeededSize()(implicit webDriver: WebDriverType) = {
    val tbodyInProgress = find(id("sv-voting-action-needed-table-body"))
    tbodyInProgress
      .map(_.findAllChildElements(className("vote-row-action")).toSeq.size)
      .getOrElse(0)
  }

  private def svAmuletPriceShouldMatch(
      rows: Seq[Element],
      svParty: PartyId,
      amuletPrice: String,
  ) = {
    forExactly(1, rows) { row =>
      seleniumText(row.childElement(className("sv-party"))) shouldBe svParty.toProtoPrimitive
      row.childElement(className("amulet-price")).text shouldBe amuletPrice
    }
  }

}
