package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.logging.SuppressionRule
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.voterequestoutcome.VRO_AcceptedButActionFailed
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.CloseVoteRequestTrigger
import org.lfdecentralizedtrust.splice.sv.config.SvOnboardingConfig.InitialPackageConfig
import org.lfdecentralizedtrust.splice.util.{
  FrontendLoginUtil,
  SvFrontendTestUtil,
  SvTestUtil,
  WalletTestUtil,
}
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.Select
import org.slf4j.event.Level

import java.nio.file.Path
import java.time.format.DateTimeFormatter
import java.time.{LocalDateTime, ZoneOffset}
import scala.concurrent.duration.DurationInt

class SvFrontendIntegrationTest
    extends SvFrontendCommonIntegrationTest
    with SvTestUtil
    with SvFrontendTestUtil
    with FrontendLoginUtil
    with WalletTestUtil
    with VotesFrontendTestUtil
    with ValidatorLicensesFrontendTestUtil {

  // TODO(#16139): change tests to work with current version (by simply getting rid of this file in favor of SvFrontendIntegrationTest2)
  private val initialPackageConfig = InitialPackageConfig(
    amuletVersion = "0.1.7",
    amuletNameServiceVersion = "0.1.7",
    dsoGovernanceVersion = "0.1.10",
    validatorLifecycleVersion = "0.1.1",
    walletVersion = "0.1.7",
    walletPaymentsVersion = "0.1.7",
  )
  // TODO(#16139): when using the latest version, this can be removed
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  private val splitwellDarPath = "daml/dars/splitwell-0.1.7.dar"

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .withNoVettedPackages(implicit env => Seq(sv1Backend.participantClient))
      .addConfigTransforms((_, config) =>
        ConfigTransforms.updateAllSvAppFoundDsoConfigs_(
          _.copy(initialPackageConfig = initialPackageConfig)
        )(config)
      )
      .addConfigTransform((_, conf) =>
        ConfigTransforms.updateAllValidatorConfigs((name, validatorConfig) =>
          if (name == "splitwellValidator")
            validatorConfig.copy(
              appInstances = validatorConfig.appInstances.updated(
                "splitwell",
                validatorConfig
                  .appInstances("splitwell")
                  .copy(
                    dars = validatorConfig.appInstances("splitwell").dars ++ Seq(
                      Path.of(splitwellDarPath)
                    )
                  ),
              )
            )
          else
            validatorConfig
        )(conf)
      )

  "Old SV UIs" should {

    def testCreateAndVoteDsoRulesAction(action: String)(
        fillUpForm: WebDriverType => Unit
    )(validateRequestedActionInModal: WebDriverType => Unit)(implicit
        env: SpliceTestConsoleEnvironment
    ) = {
      val requestReasonUrl = "https://vote-request-url.com/"
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

              setExpirationDate("sv1", "2034-07-12 00:12")

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
            find(id("tab-badge-action-needed-count")).value.text shouldBe "1"
            find(id("nav-badge-votes-count")).value.text shouldBe "1"

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
            click on "vote-confirmation-dialog-accept-button"
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
            click on "vote-confirmation-dialog-accept-button"
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
        val requestReasonUrl = "https://vote-request-url.com"
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
        val requestReasonUrl = "https://vote-request-url.com"
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
                    .ofPattern("yyyy-MM-dd HH:mm")
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
        val proposalUrl = "https://vote-request-url.com"

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
              find(id("display-actions")) should not be empty
            },
          )

          click on "tab-panel-in-progress"
          val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()

          actAndCheck(
            "sv1 operator can create a request to add a new amulet config schedule", {
              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

              clue(s"sv1 selects an effective date") {
                setAmuletConfigDate("sv1", "2032-01-04 08:00")
              }
              setExpirationDate("sv1", "2032-01-04 02:00")

              clue("sv1 operator can't click submit before adding a summary") {
                find(id("create-voterequest-submit-button")).value.isEnabled shouldBe false
              }
              clue("sv1 modifies the summary") {
                find(id("create-reason-summary")).value.underlying.sendKeys(proposalSummary)
              }

              clue("sv1 modifies the proposal url") {
                find(id("create-reason-url")).value.underlying.sendKeys(proposalUrl)
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
          val requestReasonUrl = "https://vote-request-url.com"
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
                find(id("display-actions")) should not be empty
              },
            )

            click on "tab-panel-in-progress"
            val previousVoteRequestsInProgress = getVoteRequestsInProgressSize()

            val (_, requestIdAdd) = actAndCheck(
              "sv1 operator can create a request to add a new amulet config schedule", {
                val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
                dropDownAction.selectByValue("CRARC_AddFutureAmuletConfigSchedule")

                setAmuletConfigDate("sv1", "2032-07-12 00:12")

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
                  val input = find(id("create-reason-url")).value.underlying
                  input.clear()
                  input.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "2032-07-11 00:12")

                clue("sv1 submits the vote request") {
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

                setAmuletConfigDate("sv1", "2032-07-12 00:12")

                clue("sv1 modifies one value") {
                  find(id("transferConfig.createFee.fee-value")).value.underlying
                    .sendKeys(requestNewTransferConfigFeeValue)
                }

                clue("sv1 modifies the url") {
                  val input = find(id("create-reason-url")).value.underlying
                  input.clear()
                  input.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "2032-07-11 00:12")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("voterequest-creation-alert"))) { case Some(tb) =>
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

                setAmuletConfigDate("sv1", "2033-07-12 00:12")

                clue("sv1 modifies one value") {
                  find(id("transferConfig.createFee.fee-value")).value.underlying
                    .sendKeys(requestNewTransferConfigFeeValue)
                }

                clue("sv1 modifies the url") {
                  val input = find(id("create-reason-url")).value.underlying
                  input.clear()
                  input.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "2034-07-12 00:12")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("voterequest-creation-alert"))) { case Some(tb) =>
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

              find(id("display-actions")) should not be empty
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

                setExpirationDate("sv1", "2030-07-11 00:12")

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

                clue("sv1 modifies the url") {
                  val input = find(id("create-reason-url")).value.underlying
                  input.clear()
                  input.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "2030-07-11 00:12")

                clickVoteRequestSubmitButtonOnceEnabled()
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("voterequest-creation-alert"))) { case Some(tb) =>
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

              find(id("display-actions")) should not be empty
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

                setExpirationDate("sv1", "2030-07-11 00:12")

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

                clue("sv1 modifies the url") {
                  val input = find(id("create-reason-url")).value.underlying
                  input.clear()
                  input.sendKeys(requestReasonUrl)
                }

                clue("sv1 modifies the summary") {
                  find(id("create-reason-summary")).value.underlying.sendKeys(requestReasonBody)
                }

                setExpirationDate("sv1", "2030-07-11 00:12")

                clue("sv1 creates the vote request") {
                  clickVoteRequestSubmitButtonOnceEnabled()
                }
              },
            )(
              "sv1 can see the alerting message preventing him to continue",
              _ => {
                inside(find(id("voterequest-creation-alert"))) { case Some(tb) =>
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
                click on "vote-confirmation-dialog-accept-button"
              },
            )(
              "sv2's modal closes as all SVs voted before the expiration date",
              _ => {
                find(id("accept-vote-button")) shouldBe empty
              },
            )
          }
      }

    "if two AddFutureAmuletConfigSchedule actions scheduled at the same time are created concurrently, then only one succeeds" in {
      implicit env =>
        val requestReasonUrl = "https://vote-request-url.com"
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

            setAmuletConfigDate("sv1", "2030-07-12 00:12")

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
            setExpirationDate("sv1", "2030-07-11 00:12")
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

            setAmuletConfigDate("sv2", "2030-07-12 00:12")

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
            setExpirationDate("sv2", "2030-07-11 00:12")
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

}
