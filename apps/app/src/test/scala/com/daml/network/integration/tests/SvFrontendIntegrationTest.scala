package com.daml.network.integration.tests

import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.CNNodeTestConsoleEnvironment
import com.daml.network.util.{FrontendLoginUtil, SvTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.By
import org.openqa.selenium.support.ui.Select

class SvFrontendIntegrationTest
    extends FrontendIntegrationTest("sv1", "sv2")
    with SvTestUtil
    with FrontendLoginUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)

  "SV UIs" should {
    val sv1Port = 3010
    val sv2Port = 3012

    "have basic login functionality" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "login works with correct password", {
            login(sv1Port, sv1Backend.config.ledgerApiUser)
          },
        )(
          "logged in in the sv ui",
          _ => find(id("app-title")).value.text should matchText("SUPER VALIDATOR OPERATIONS"),
        )
      }
    }

    "warn if user fails to login" in { _ =>
      withFrontEnd("sv1") { implicit webDriver =>
        loggerFactory.assertLogs(
          {
            actAndCheck(
              "login does not work with wrong user", {
                login(sv1Port, "WrongUser")
              },
            )(
              "login fails",
              _ =>
                find(id("loginFailed")).value.text should matchText(
                  "User unauthorized to act as the SV Party."
                ),
            )
          },
          _.warningMessage should include(
            "Authorization Failed"
          ),
        )
      }
    }

    "have 5 information tabs" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "svc and coin infos are displayed in pretty json", {
            login(sv1Port, sv1Backend.config.ledgerApiUser)
          },
        )(
          "We see the 5 tab panels",
          _ => {
            inside(find(id("information-tab-general"))) { case Some(e) =>
              e.text shouldBe "General"
            }
            inside(find(id("information-tab-svc-configuration"))) { case Some(e) =>
              e.text shouldBe "SVC Configuration"
            }
            inside(find(id("information-tab-cc-configuration"))) { case Some(e) =>
              e.text shouldBe "Canton Coin Configuration"
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
            val valueCells = findAll(className("general-svc-value-name")).toSeq
            valueCells should have length 9
            forExactly(1, valueCells)(
              _.text should matchText(sv1Backend.config.ledgerApiUser)
            )
            forExactly(3, valueCells)(
              _.text should matchText(sv1Backend.getSvcInfo().svParty.toProtoPrimitive)
            )
          },
        )
        actAndCheck("Click on domain status tab", click on "information-tab-canton-domain-status")(
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
            go to s"http://localhost:$sv1Port/validator-onboarding"
            loginOnCurrentPage(sv1Port, sv1Backend.config.ledgerApiUser)
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

        val licenseRows = findAll(className("validator-licenses-table-row")).toList
        val newValidatorParty = allocateRandomSvParty("validatorX")

        actAndCheck(
          "onboard new validator using the secret",
          sv1Backend.onboardValidator(newValidatorParty, newSecret),
        )(
          "a new validator row is added",
          _ => {
            val newLicenseRows = findAll(className("validator-licenses-table-row")).toList
            newLicenseRows should have size (licenseRows.size + 1L)
            val row: Element = inside(newLicenseRows) { case row :: _ =>
              row
            }
            val sponsor =
              row.childElement(className("validator-licenses-sponsor")).text
            val validator =
              row.childElement(className("validator-licenses-validator")).text
            sponsor shouldBe sv1Backend.getSvcInfo().svParty.toProtoPrimitive
            validator shouldBe newValidatorParty.toProtoPrimitive
          },
        )
      }
    }

    "can view median coin price and update desired coin price by each SV" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "sv1 operator can login and browse to the coin price tab", {
            go to s"http://localhost:$sv1Port/cc-price"
            loginOnCurrentPage(sv1Port, sv1Backend.config.ledgerApiUser)
          },
        )(
          "We see a median coin price, desired coin price of SV1 and other SVs, open mining rounds",
          _ => {
            inside(find(id("median-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "1 USD"
            }
            inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
              e.text shouldBe "1 USD"
            }
            val rows = findAll(className("coin-price-table-row")).toSeq
            rows should have size 3
            svCoinPriceShouldMatch(rows, sv2Backend.getSvcInfo().svParty, "1 USD")
            svCoinPriceShouldMatch(rows, sv3Backend.getSvcInfo().svParty, "Not Set")
            svCoinPriceShouldMatch(rows, sv4Backend.getSvcInfo().svParty, "Not Set")

            val roundRows = findAll(className("open-mining-round-row")).toSeq
            roundRows should have size 3
            forEvery(roundRows) {
              _.childElement(className("coin-price")).text shouldBe "1 USD"
            }
          },
        )

        val testDesiredPriceChange = (desiredPrice: BigDecimal, otherValues: Seq[BigDecimal]) => {
          inside(find(id("median-coin-price-usd"))) { case Some(e) =>
            e.text shouldBe s"${median(Seq(desiredPrice) ++ otherValues).getOrElse('0')} USD"
          }
          inside(find(id("cur-sv-coin-price-usd"))) { case Some(e) =>
            e.text shouldBe s"${desiredPrice.toString()} USD"
          }
          val rows = findAll(className("coin-price-table-row")).toSeq
          rows should have size 3
          svCoinPriceShouldMatch(
            rows,
            sv2Backend.getSvcInfo().svParty,
            if (otherValues.isDefinedAt(0)) s"${otherValues(0)} USD" else "Not Set",
          )
          svCoinPriceShouldMatch(
            rows,
            sv3Backend.getSvcInfo().svParty,
            if (otherValues.isDefinedAt(1)) s"${otherValues(1)} USD" else "Not Set",
          )
          svCoinPriceShouldMatch(
            rows,
            sv4Backend.getSvcInfo().svParty,
            if (otherValues.isDefinedAt(2)) s"${otherValues(2)} USD" else "Not Set",
          )
        }

        actAndCheck(
          "sv1 operator can change the desired price", {
            click on "edit-coin-price-button"
            click on "desired-coin-price-field"
            numberField("desired-coin-price-field").underlying.clear()
            numberField("desired-coin-price-field").underlying.sendKeys("10.55")

            click on "update-coin-price-button"
          },
        )(
          "median fractional coin price changed and coin price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10.55, Seq(1))
          },
        )

        actAndCheck(
          "sv1 operator can change the desired price", {
            click on "edit-coin-price-button"
            click on "desired-coin-price-field"
            numberField("desired-coin-price-field").underlying.clear()
            numberField("desired-coin-price-field").underlying.sendKeys("10")

            click on "update-coin-price-button"
          },
        )(
          "median coin price changed and coin price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10, Seq(1))
          },
        )

        actAndCheck(
          "sv2 set the desired price", {
            sv2Backend.updateCoinPriceVote(BigDecimal(15.55))
          },
        )(
          "median coin price changed and coin price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10, Seq(15.55))
          },
        )

        actAndCheck(
          "sv3 set the desired price", {
            sv3Backend.updateCoinPriceVote(BigDecimal(5))
          },
        )(
          "median coin price changed and coin price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10, Seq(15.55, 5))
          },
        )

        actAndCheck(
          "sv4 set the desired price", {
            sv4Backend.updateCoinPriceVote(BigDecimal(9.0))
          },
        )(
          "median coin price changed and coin price updated on the row for sv4",
          _ => {
            testDesiredPriceChange(10, Seq(15.55, 5, 9))
          },
        )

        actAndCheck(
          "sv1 update the desired price", {
            sv1Backend.updateCoinPriceVote(BigDecimal(1.0))
          },
        )(
          "median coin price changed",
          _ => {
            testDesiredPriceChange(1, Seq(15.55, 5, 9))
          },
        )
      }
    }

    "can create a valid SRARC_RemoveMember vote request and cast vote on it" in { implicit env =>
      val requestReasonUrl = "This is a request reason url."
      val requestReasonBody = "This is a request reason."
      val (createdVoteRequestAction, createdVoteRequestRequester) = withFrontEnd("sv1") {
        implicit webDriver =>
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1Port/votes"
              loginOnCurrentPage(sv1Port, sv1Backend.config.ledgerApiUser)
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
              dropDownAction.selectByValue("SRARC_RemoveMember")

              val dropDownMember = new Select(webDriver.findElement(By.id("display-members")))
              dropDownMember.selectByIndex(3)

              inside(find(id("create-reason-url"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonUrl)
              }
              inside(find(id("create-reason-description"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }

              click on "create-voterequest-submit-button"
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              val tbody = find(id("sv-voting-in-progress-table-body"))
              inside(tbody) { case Some(tb) =>
                val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
                rows should have size 1
                (
                  rows.head.childElement(className("vote-row-action")).text,
                  rows.head.childElement(className("vote-row-requester")).text,
                )
              }
            },
          )
          (createdVoteRequestAction, createdVoteRequestRequester)
      }

      withFrontEnd("sv2") { implicit webDriver =>
        val (_, reviewButton) = actAndCheck(
          "sv2 operator can login and browse to the governance tab", {
            go to s"http://localhost:$sv2Port/votes"
            loginOnCurrentPage(sv2Port, sv2Backend.config.ledgerApiUser)
          },
        )(
          "sv2 can see the new vote request",
          _ => {
            val tbody = find(id("sv-voting-action-needed-table-body"))
            inside(tbody) { case Some(tb) =>
              val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
              rows should have size 1
              rows.head.childElement(className("vote-row-action")).text should matchText(
                createdVoteRequestAction
              )
              rows.head.childElement(className("vote-row-requester")).text should matchText(
                createdVoteRequestRequester
              )
              val reviewButton = rows.head
                .childElement(className("vote-row-review"))
                .childElement(className("vote-row-review-button"))
              reviewButton.text should matchText("REVIEW")
              reviewButton.underlying
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
              element.text should matchText("ARC_SvcRules")
            }
            inside(find(id("vote-request-modal-action-name"))) { case Some(element) =>
              element.text should matchText("SRARC_RemoveMember")
            }
            inside(find(id("vote-request-modal-requested-by"))) { case Some(element) =>
              element.text should matchText(sv1Backend.getSvcInfo().svParty.toProtoPrimitive)
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
            val tbody = find(id("sv-voting-in-progress-table-body"))
            inside(tbody) { case Some(tb) =>
              val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
              rows should have size 1
              val reviewButton = rows.head
                .childElement(className("vote-row-review"))
                .childElement(className("vote-row-review-button"))
              reviewButton.text should matchText("REVIEW")
              reviewButton.underlying.click()
            }
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

    "can create valid SRARC_GrantFeaturedAppRight and SRARC_RevokeFeaturedAppRight vote requests" in {
      implicit env =>
        val requestProviderParty = "TestProviderParty"
        val requestReasonUrl = "This is a request reason url."
        val requestReasonBody = "This is a request reason."

        withFrontEnd("sv1") { implicit webDriver =>
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1Port/votes"
              loginOnCurrentPage(sv1Port, sv1Backend.config.ledgerApiUser)
            },
          )(
            "sv1 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )

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
              inside(find(id("create-reason-description"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }

              click on "create-voterequest-submit-button"
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              val tbody = find(id("sv-voting-in-progress-table-body"))
              inside(tbody) { case Some(tb) =>
                val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
                rows should have size 1
                (
                  rows.head.childElement(className("vote-row-action")).text,
                  rows.head.childElement(className("vote-row-requester")).text,
                )
              }
            },
          )

          val (_, rightCid) = actAndCheck(
            "sv1 operator can see the vote request detail by clicking review button", {
              val tbody = find(id("sv-voting-in-progress-table-body"))
              inside(tbody) { case Some(tb) =>
                val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
                rows should have size 1
                val reviewButton = rows.head
                  .childElement(className("vote-row-review"))
                  .childElement(className("vote-row-review-button"))
                reviewButton.text should matchText("REVIEW")
                reviewButton.underlying.click()
              }
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
              go to s"http://localhost:$sv1Port/votes"

              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue("SRARC_RevokeFeaturedAppRight")

              inside(find(id("set-application-rightcid"))) { case Some(element) =>
                element.underlying.sendKeys(rightCid)
              }

              inside(find(id("create-reason-url"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonUrl)
              }
              inside(find(id("create-reason-description"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }

              click on "create-voterequest-submit-button"
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              val tbody = find(id("sv-voting-in-progress-table-body"))
              inside(tbody) { case Some(tb) =>
                val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
                rows should have size 2
                (
                  rows.head.childElement(className("vote-row-action")).text,
                  rows.head.childElement(className("vote-row-requester")).text,
                )
              }
            },
          )
        }
    }

    "can create a valid SRARC_SetConfig (new SvcRules Configuration) vote request" in {
      implicit env =>
        val requestNewNumUnclaimedRewardsThreshold = "42"
        val requestReasonUrl = "This is a request reason url."
        val requestReasonBody = "This is a request reason."

        withFrontEnd("sv1") { implicit webDriver =>
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1Port/votes"
              loginOnCurrentPage(sv1Port, sv1Backend.config.ledgerApiUser)
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
              dropDownAction.selectByValue("SRARC_SetConfig")

              inside(find(id("numUnclaimedRewardsThreshold-value"))) { case Some(element) =>
                element.underlying.sendKeys(requestNewNumUnclaimedRewardsThreshold)
              }
              inside(find(id("create-reason-url"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonUrl)
              }
              inside(find(id("create-reason-description"))) { case Some(element) =>
                element.underlying.sendKeys(requestReasonBody)
              }

              click on "create-voterequest-submit-button"
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              val tbody = find(id("sv-voting-in-progress-table-body"))
              inside(tbody) { case Some(tb) =>
                val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
                rows should have size 1
                (
                  rows.head.childElement(className("vote-row-action")).text,
                  rows.head.childElement(className("vote-row-requester")).text,
                )
              }
            },
          )
          (createdVoteRequestAction, createdVoteRequestRequester)
        }

    }

    "can create a valid CRARC_SetConfigSchedule(future coin configurations) vote request" in {
      implicit env =>
        val requestNewTransferConfigFeeValue = "42"
        val requestReasonUrl = "This is a request reason url."
        val requestReasonBody = "This is a request reason."

        withFrontEnd("sv1") { implicit webDriver =>
          actAndCheck(
            "sv1 operator can login and browse to the governance tab", {
              go to s"http://localhost:$sv1Port/votes"
              loginOnCurrentPage(sv1Port, sv1Backend.config.ledgerApiUser)
            },
          )(
            "sv1 can see the create vote request button",
            _ => {
              find(id("create-voterequest-submit-button")) should not be empty
            },
          )

          actAndCheck(
            "sv1 operator can create a new vote request with two future schedules on different dates", {
              val dropDownAction = new Select(webDriver.findElement(By.id("display-actions")))
              dropDownAction.selectByValue("CRARC_SetConfigSchedule")

              Seq(("12-12-002030T12:12", 1), ("12-12-002031T12:12", 2)).foreach(e => {
                inside(find(id("datetime-schedule-configuration-value"))) { case Some(element) =>
                  element.underlying.clear()
                  element.underlying.sendKeys(e._1)
                }

                click on "button-schedule-new-configuration"

                click on s"button-collapse-schedule-configuration-${e._2}"

                clue("sv1 modifies one value") {
                  find(id("transferConfig.createFee.fee-value")).value.underlying
                    .sendKeys(requestNewTransferConfigFeeValue)
                }

              })
              clue("sv1 modifies url") {
                find(id("create-reason-url")).value.underlying.sendKeys(requestReasonUrl)
              }

              clue("sv1 modifies description") {
                find(id("create-reason-description")).value.underlying.sendKeys(requestReasonBody)
              }

              click on "create-voterequest-submit-button"
            },
          )(
            "sv1 can see the new vote request",
            _ => {
              val tbody = find(id("sv-voting-in-progress-table-body"))
              val tb = tbody.value
              val rows = tb.findAllChildElements(className("vote-request-row")).toSeq
              rows should have size 1
              rows.head
                .childElement(className("vote-row-action"))
                .text shouldBe "CRARC_SetConfigSchedule"

              val reviewButton = rows.head
                .childElement(className("vote-row-review"))
                .childElement(className("vote-row-review-button"))
              reviewButton.text should matchText("REVIEW")
              reviewButton.underlying.click()

              val dropDownSchedules =
                new Select(webDriver.findElement(By.id("dropdown-display-schedules-datetime")))
              dropDownSchedules.getOptions.size() shouldBe 2

            },
          )
        }

    }

  }

  private def svCoinPriceShouldMatch(
      rows: Seq[Element],
      svParty: PartyId,
      coinPrice: String,
  ) = {
    forExactly(1, rows) { row =>
      row.childElement(className("sv-party")).text shouldBe svParty.toProtoPrimitive
      row.childElement(className("coin-price")).text shouldBe coinPrice
    }
  }
}
