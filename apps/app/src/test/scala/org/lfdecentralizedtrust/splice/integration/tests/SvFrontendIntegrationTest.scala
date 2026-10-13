package org.lfdecentralizedtrust.splice.integration.tests

import com.digitalasset.canton.logging.SuppressionRule
import com.digitalasset.canton.topology.{PartyId, SynchronizerId}
import org.lfdecentralizedtrust.splice.codegen.java.da.time.types.RelTime
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.actionrequiringconfirmation.{
  ARC_AmuletRules,
  ARC_DsoRules,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.dsorules_actionrequiringconfirmation.{
  SRARC_OffboardSv,
  SRARC_SetConfig,
}
import org.lfdecentralizedtrust.splice.codegen.java.splice.dsorules.{
  ActionRequiringConfirmation,
  DsoRules_OffboardSv,
  DsoRules_SetConfig,
  VoteRequest,
}
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.store.VoteResultsFilters
import org.lfdecentralizedtrust.splice.sv.automation.delegatebased.CloseVoteRequestTrigger
import org.lfdecentralizedtrust.splice.util.SpliceUtil.defaultDsoRulesConfig
import org.lfdecentralizedtrust.splice.util.*
import org.openqa.selenium.By
import org.slf4j.event.Level

import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import java.util.Optional

class SvFrontendIntegrationTest
    extends SvFrontendCommonIntegrationTest
    with AmuletConfigUtil
    with SvTestUtil
    with SvFrontendTestUtil
    with FrontendLoginUtil
    with WalletTestUtil
    with VotesFrontendTestUtil
    with ValidatorLicensesFrontendTestUtil {

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology4Svs(this.getClass.getSimpleName)
      .addConfigTransforms(
        // We deliberately change votes quickly in this test
        (_, config) => ConfigTransforms.withNoVoteCooldown(config)
      )

  def testId(id: String) = cssSelector(s"[data-testid='$id']")

  "SV UIs" should {
    "have basic login functionality" in { implicit env =>
      withFrontEnd("sv1") { implicit webDriver =>
        actAndCheck(
          "login works with correct password", {
            login(sv1UIPort, sv1Backend.config.ledgerApiUser)
          },
        )(
          "logged in in the sv ui",
          _ => find(id("app-title")).value.text should matchText("Supervalidator Operations"),
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
            find(
              className("onboarding-secret-table")
            ) should not be empty withClue "'Validator Onboarding Secrets' table"
            val rows = findAll(className("onboarding-secret-table-row")).toSeq
            find(id("create-party-hint")) should not be empty withClue "'Party Hint' textfield"
            find(
              id("create-validator-onboarding-secret")
            ) should not be empty withClue "'Create a validator onboarding secret' button"
            rows.size
          },
        )

        val (_, newSecret) = actAndCheck(
          "fill the party hint field and click on the button to create an onboarding secret", {
            clue("fill party hint") {
              inside(find(id("create-party-hint"))) { case Some(element) =>
                element.underlying.sendKeys("splice-client-2")
              }
            }

            clue("wait for the create button to become enabled") {
              eventually() {
                find(id("create-validator-onboarding-secret")).value.isEnabled shouldBe true
              }
            }

            clue("click the create validator onboarding secret button") {
              eventuallyClickOn(id("create-validator-onboarding-secret"))
            }
          },
        )(
          "a new secret row is added",
          _ => {
            val secrets = findAll(
              className("onboarding-secret-table-secret")
            ).toSeq
            secrets should have size (rowSize + 1L) withClue "rows in 'Validator Onboarding Secrets' table"
            secrets.head.text
          },
        )

        val licenseRows = getLicensesTableRows
        val newValidatorParty = allocateRandomSvParty("splice-client", Some(2))

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
            checkValidatorLicenseRow(
              licenseRows.size.toLong,
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
            rows should have size 3 withClue "'Desired AMT Prices of Other Super Validators' table rows"
            svAmuletPriceShouldMatch(rows, sv2Backend.getDsoInfo().svParty, shownAmuletPrice)
            svAmuletPriceShouldMatch(rows, sv3Backend.getDsoInfo().svParty, "Not Set")
            svAmuletPriceShouldMatch(rows, sv4Backend.getDsoInfo().svParty, "Not Set")

            val roundRows = findAll(className("open-mining-round-row")).toSeq
            roundRows should have size 3 withClue "'Open Mining Rounds' table rows"
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
          rows should have size 3 withClue "'Desired AMT Prices of Other Super Validators' table rows"
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
            eventuallyClickOn(id("edit-amulet-price-button"))
            eventuallyClickOn(id("desired-amulet-price-field"))
            numberField("desired-amulet-price-field").underlying.clear()
            numberField("desired-amulet-price-field").underlying.sendKeys("10.55")

            eventuallyClickOn(id("update-amulet-price-button"))
          },
        )(
          "median fractional amulet price changed and amulet price updated on the row for sv2",
          _ => {
            testDesiredPriceChange(10.55, Seq(walletAmuletPrice))
          },
        )

        actAndCheck(
          "sv1 operator can change the desired price", {
            eventuallyClickOn(id("edit-amulet-price-button"))
            eventuallyClickOn(id("desired-amulet-price-field"))
            numberField("desired-amulet-price-field").underlying.clear()
            numberField("desired-amulet-price-field").underlying.sendKeys("10")

            eventuallyClickOn(id("update-amulet-price-button"))
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

    def loginToGovernance(uiPort: Int, ledgerApiUser: String)(implicit
        webDriver: WebDriverType
    ): Unit =
      actAndCheck(
        "operator can login and browse to the governance page", {
          go to s"http://localhost:$uiPort/governance"
          loginOnCurrentPage(uiPort, ledgerApiUser)
        },
      )(
        "can see the governance page",
        _ =>
          find(
            id("initiate-proposal-button")
          ) should not be empty withClue "'Initiate Proposal' button",
      )

    def selectActionAndNavigateToForm(action: String, formPrefix: String)(implicit
        webDriver: WebDriverType
    ): Unit =
      actAndCheck(
        "sv1 can navigate to the create proposal page", {
          click on id("initiate-proposal-button")

          clue("select action and click next") {
            eventually() {
              val actionDropdown = webDriver.findElement(By.id("select-action"))
              actionDropdown.click()
            }

            eventually() {
              val actionOption =
                webDriver.findElement(By.cssSelector(s"[data-testid='$action']"))
              actionOption.click()
            }

            eventually() {
              click on id("next-button")
            }
          }
        },
      )(
        "sv1 can see the create proposal form",
        _ =>
          find(
            id(s"$formPrefix-summary")
          ) should not be empty withClue s"$formPrefix create proposal form",
      )

    def fillAndSubmitProposalForm(
        formPrefix: String,
        requestReasonBody: String,
        requestReasonUrl: String,
        effectiveAtThreshold: Boolean,
        extraFormOps: WebDriverType => Unit,
    )(implicit webDriver: WebDriverType): Unit = {
      actAndCheck(
        "sv1 operator can create a new proposal", {
          if (effectiveAtThreshold) {
            eventually() {
              val effectiveAtThresholdRadio =
                webDriver.findElement(By.id("effective-at-threshold-radio"))
              effectiveAtThresholdRadio.click()
            }
          }

          extraFormOps(webDriver)

          eventually() {
            inside(find(id(s"$formPrefix-summary"))) { case Some(element) =>
              element.underlying.sendKeys(requestReasonBody)
            }
          }

          eventually() {
            inside(find(id(s"$formPrefix-url"))) { case Some(element) =>
              element.underlying.sendKeys(requestReasonUrl)
            }
          }

          eventually() {
            val submitButton = webDriver.findElement(By.id("submit-button"))
            submitButton.click()
          }

          eventually() {
            val submitButton = webDriver.findElement(By.id("submit-button"))
            submitButton.getText shouldBe "Submit Proposal"
          }

          eventually() {
            val submitButton = webDriver.findElement(By.id("submit-button"))
            submitButton.click()
          }
        },
      )(
        "sv1 is redirected to the governance page after successful submission",
        _ => {
          find(
            id("initiate-proposal-button")
          ) should not be empty withClue "'Initiate Proposal' button"
          val proposals = getInflightProposals()
          proposals.size should be > 0
        },
      )
    }

    def sv2CastVoteOnActionRequired(proposalContractId: String, accept: Boolean = true)(implicit
        webDriver: WebDriverType,
        env: SpliceTestConsoleEnvironment,
    ): Unit = {
      actAndCheck(
        "sv2 operator can login and browse to the proposal details page", {
          go to s"http://localhost:$sv2UIPort/governance/proposals/$proposalContractId"
          loginOnCurrentPage(sv2UIPort, sv2Backend.config.ledgerApiUser)
        },
      )(
        "sv2 can see the vote form",
        _ =>
          find(
            testId("your-vote-reason-input")
          ) should not be empty withClue "'Action Required' Box",
      )

      actAndCheck(
        s"sv2 fills out and submits a vote to ${if (accept) "accept" else "reject"}", {
          inside(find(testId("your-vote-reason-input"))) { case Some(element) =>
            element.underlying.sendKeys("A sample reason")
          }

          inside(find(testId("your-vote-url-input"))) { case Some(element) =>
            element.underlying.sendKeys("https://my-splice-vote-url.com")
          }

          click on testId(if (accept) "your-vote-accept" else "your-vote-reject")
        },
      )(
        "the vote submission success message is shown",
        _ =>
          inside(find(testId("vote-submission-success"))) { case Some(element) =>
            element.text shouldBe "Vote successfully updated!"
          },
      )
    }

    def sv1VerifyVoteFromSv2(proposalContractId: String)(implicit
        webDriver: WebDriverType,
        env: SpliceTestConsoleEnvironment,
    ): Unit =
      actAndCheck(
        "sv1 navigates back to the proposal details page", {
          go to s"http://localhost:$sv1UIPort/governance/proposals/$proposalContractId"
        },
      )(
        "sv1 can see the new vote from sv2",
        _ => {
          val sv2PartyId = sv2Backend.getDsoInfo().svParty.toProtoPrimitive
          val sv2PartyHint = sv2PartyId.split("::").head
          val votes =
            webDriver.findElements(By.cssSelector("[data-testid='proposal-details-vote']"))
          votes.size should be >= 1

          val voterPartyTexts = votes.asScala.map { vote =>
            vote
              .findElement(
                By.cssSelector("[data-testid='proposal-details-voter-party-id-value']")
              )
              .getText
          }
          voterPartyTexts.exists(_.startsWith(sv2PartyHint)) shouldBe true
        },
      )

    def voteRequestActionName(
        request: Contract[VoteRequest.ContractId, VoteRequest]
    ): String =
      request.payload.action match {
        case dsoAction: ARC_DsoRules => dsoAction.dsoAction.getClass.getSimpleName
        case amuletAction: ARC_AmuletRules =>
          amuletAction.amuletRulesAction.getClass.getSimpleName
        case otherAction => otherAction.getClass.getSimpleName
      }

    def assertCreateProposal(
        action: String,
        formPrefix: String,
        effectiveAtThreshold: Boolean = true,
    )(
        extraFormOps: WebDriverType => Unit
    )(implicit
        env: SpliceTestConsoleEnvironment
    ): String = clue(s"Creating proposal: $action") {
      val requestReasonUrl = "https://new-proposal-url.com/"
      val requestReasonBody = "This is a summary of the proposal"
      val existingProposalTrackingCids =
        sv1Backend.listVoteRequests().map(getTrackingId(_).contractId).toSet

      withFrontEnd("sv1") { implicit webDriver =>
        loginToGovernance(sv1UIPort, sv1Backend.config.ledgerApiUser)
        selectActionAndNavigateToForm(action, formPrefix)
        fillAndSubmitProposalForm(
          formPrefix,
          requestReasonBody,
          requestReasonUrl,
          effectiveAtThreshold,
          extraFormOps,
        )
      }
      val proposalContractId = eventually() {
        val newRequests = sv1Backend.listVoteRequests().filter { request =>
          val trackingCid = getTrackingId(request).contractId
          !existingProposalTrackingCids.contains(trackingCid)
        }
        val createdRequest = newRequests
          .find { request =>
            voteRequestActionName(request) == action
          }
          .getOrElse {
            val newActions = newRequests.map(voteRequestActionName).distinct.sorted.mkString(", ")
            fail(
              s"Could not find created vote request for action $action. New vote request actions after submission: [$newActions]"
            )
          }
        getTrackingId(createdRequest).contractId
      }

      withFrontEnd("sv2") { implicit webDriver =>
        sv2CastVoteOnActionRequired(proposalContractId)
      }

      withFrontEnd("sv1") { implicit webDriver =>
        sv1VerifyVoteFromSv2(proposalContractId)
      }

      proposalContractId
    }

    "Offboard SV" in { implicit env =>
      val sv3PartyId = sv3Backend.getDsoInfo().svParty.toProtoPrimitive

      assertCreateProposal("SRARC_OffboardSv", "offboard-sv") { implicit webDriver =>
        selectMuiOptionByValue("offboard-sv-member-dropdown", sv3PartyId)
      }
    }

    "Offboard SV with custom effective date" in { implicit env =>
      val sv4PartyId = sv4Backend.getDsoInfo().svParty.toProtoPrimitive
      val effectiveDate = "2099-01-31 00:12"

      assertCreateProposal("SRARC_OffboardSv", "offboard-sv", false) { implicit webDriver =>
        setEffectiveDate("sv1", "offboard-sv", effectiveDate)

        eventually() {
          val dropdown = webDriver.findElement(By.id("offboard-sv-member-dropdown"))
          dropdown.click()

          val memberOption = webDriver.findElement(By.cssSelector(s"[data-value='$sv4PartyId']"))
          memberOption.click()
        }
        selectMuiOptionByValue("offboard-sv-member-dropdown", sv4PartyId)
      }
    }

    "Grant, Update and Revoke Featured App Right" in { implicit env =>
      val providerParty = sv3Backend.getDsoInfo().svParty
      val providerPartyId = providerParty.toProtoPrimitive
      val activityWeight = BigDecimal("2.5")

      // First, create a Grant proposal for the provider.
      val grantProposalContractId = assertCreateProposal(
        "SRARC_GrantFeaturedAppRight",
        "grant-featured-app",
      ) { implicit webDriver =>
        fillOutTextField("grant-featured-app-idValue", providerPartyId)
        fillOutTextField("grant-featured-app-activityWeight", activityWeight.toString)
      }

      clue("vote the grant request to execution before creating revoke request") {
        val grantTrackingCid = eventually() {
          val voteRequest: Contract[VoteRequest.ContractId, VoteRequest] =
            getVoteRequestForProposal(grantProposalContractId)

          if (voteRequest.payload.trackingCid.isPresent) voteRequest.payload.trackingCid.get
          else voteRequest.contractId
        }

        // With 4 SVs, 3 votes pass a request: sv1 (requester) and sv2 already
        // voted in assertCreateProposal, so sv3's vote here reaches the threshold
        // and archives the VoteRequest. We don't cast sv4's redundant vote: it
        // races the archival and ~once a month fails with NOT_FOUND, flaking.
        eventuallySucceeds() {
          sv3Backend.castVote(grantTrackingCid, true, "", "")
        }

        eventually() {
          val featuredAppRight = sv1ScanBackend.lookupFeaturedAppRight(providerParty)
          featuredAppRight shouldBe a[Some[?]]
          featuredAppRight.value.payload.activityWeight.toScala.map(
            BigDecimal(_)
          ) shouldBe Some(activityWeight)
        }
      }

      val newActivityWeight = BigDecimal("3.0")

      val updateProposalContractId = assertCreateProposal(
        "SRARC_UpdateFeaturedAppRight",
        "update-featured-app",
      ) { implicit webDriver =>
        fillOutTextField("update-featured-app-partyId", providerPartyId)
        selectFirstMuiOption("update-featured-app-rightCid-dropdown")
        fillOutTextField("update-featured-app-activityWeight", newActivityWeight.toString)
      }

      clue("vote the update request to execution") {
        val updateTrackingCid = eventually() {
          val voteRequest = getVoteRequestForProposal(updateProposalContractId)
          if (voteRequest.payload.trackingCid.isPresent) voteRequest.payload.trackingCid.get
          else voteRequest.contractId
        }

        eventuallySucceeds() {
          sv3Backend.castVote(updateTrackingCid, isAccepted = true, "", "")
        }

        eventually() {
          val featuredAppRight = sv1ScanBackend.lookupFeaturedAppRight(providerParty)
          featuredAppRight shouldBe a[Some[?]]
          featuredAppRight.value.payload.activityWeight.toScala.map(
            BigDecimal(_)
          ) shouldBe Some(newActivityWeight)
        }
      }

      // Now create a Revoke proposal by selecting a contract ID from the provider's dropdown.
      assertCreateProposal("SRARC_RevokeFeaturedAppRight", "revoke-featured-app") {
        implicit webDriver =>
          fillOutTextField("revoke-featured-app-partyId", providerPartyId)
          selectFirstMuiOption("revoke-featured-app-rightCid-dropdown")
      }
    }

    "Set Dso Rules Configuration" in { implicit env =>
      assertCreateProposal("SRARC_SetConfig", "set-dso-config-rules") { implicit webDriver =>
        eventually() {
          inside(find(testId("config-field-numUnclaimedRewardsThreshold"))) { case Some(element) =>
            element.underlying.sendKeys("99")
          }
        }
      }
    }

    "Create Unclaimed Activity Record" in { implicit env =>
      val beneficiary = sv3Backend.getDsoInfo().svParty.toProtoPrimitive
      val amount = "100.5"

      assertCreateProposal(
        "SRARC_CreateUnallocatedUnclaimedActivityRecord",
        "create-unallocated-unclaimed-activity-record",
      ) { implicit webDriver =>
        fillOutTextField("create-unallocated-unclaimed-activity-record-beneficiary", beneficiary)
        fillOutTextField("create-unallocated-unclaimed-activity-record-amount", amount)
      }
    }

    "Set Amulet Rules Configuration" in { implicit env =>
      assertCreateProposal("CRARC_SetConfig", "set-amulet-config-rules") { implicit webDriver =>
        eventually() {
          inside(find(testId("config-field-transferPreapprovalFee"))) { case Some(element) =>
            element.underlying.sendKeys("99")
          }
        }
      }
    }

    "Update SV Reward Weight" in { implicit env =>
      val sv3PartyId = sv3Backend.getDsoInfo().svParty.toProtoPrimitive
      val newWeight = "0_5000"

      assertCreateProposal("SRARC_UpdateSvRewardWeight", "update-sv-reward-weight") {
        implicit webDriver =>
          selectMuiOptionByValue("update-sv-reward-weight-member-dropdown", sv3PartyId)
          fillOutTextField("update-sv-reward-weight-weight", newWeight)
      }
    }

    "Set Dso Rules Configuration proposals can be rejected by other SVs and can expire" in {
      implicit env =>
        val requestReasonUrl = "https://new-proposal-url.com/"
        // A VoteRequest only gets a trackingCid once somebody votes on it, so the UI cannot show a
        // stable contract id for a request that expires untouched. Match rows by description instead.
        val expiringProposalReason = "This proposal expires before anybody votes on it"
        val rejectedProposalReason = "This proposal gets rejected by the other SVs"

        def rowDescriptions(section: String)(implicit webDriver: WebDriverType): Seq[String] =
          webDriver
            .findElements(By.cssSelector(s"[data-testid='$section-row-description']"))
            .asScala
            .map(_.getText)
            .toSeq

        def voteHistoryStatus(reason: String)(implicit webDriver: WebDriverType): Option[String] =
          webDriver
            .findElements(By.cssSelector("[data-testid='vote-history-row']"))
            .asScala
            .find(
              _.findElement(
                By.cssSelector("[data-testid='vote-history-row-description']")
              ).getText == reason
            )
            .map(_.findElement(By.cssSelector("[data-testid='vote-history-row-status']")).getText)

        def newVoteRequestWithReason(reason: String): VoteRequest.ContractId =
          eventually() {
            getTrackingId(
              sv1Backend.listVoteRequests().filter(_.payload.reason.body == reason).loneElement
            )
          }

        // Creates a SetConfig proposal via the backend with a short expiry so it expires on its own
        def createShortLivedSetConfigProposal(): Unit = {
          val activeSynchronizerId =
            AmuletConfigSchedule(sv1Backend.getDsoInfo().amuletRules)
              .getConfigAsOf(env.environment.clock.now)
              .decentralizedSynchronizer
              .activeSynchronizer
          val baseConfig =
            defaultDsoRulesConfig(1, 2, 3, SynchronizerId.tryFromString(activeSynchronizerId))
          val newConfig =
            defaultDsoRulesConfig(41, 2, 3, SynchronizerId.tryFromString(activeSynchronizerId))
          val setDsoConfigAction: ActionRequiringConfirmation = new ARC_DsoRules(
            new SRARC_SetConfig(new DsoRules_SetConfig(newConfig, Optional.of(baseConfig)))
          )
          sv1Backend.createVoteRequest(
            sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
            setDsoConfigAction,
            requestReasonUrl,
            expiringProposalReason,
            new RelTime(java.time.Duration.ofSeconds(10).toMillis * 1000L),
            None,
          )
        }

        clue("Pausing vote request expiration automation") {
          sv1Backend.dsoDelegateBasedAutomation
            .trigger[CloseVoteRequestTrigger]
            .pause()
            .futureValue
        }

        actAndCheck(
          "sv1 creates a proposal with a short expiration time",
          createShortLivedSetConfigProposal(),
        )(
          "the short-lived proposal exists",
          _ => newVoteRequestWithReason(expiringProposalReason),
        )

        val rejectedProposalCid = clue("sv1 creates a proposal via the UI") {
          withFrontEnd("sv1") { implicit webDriver =>
            loginToGovernance(sv1UIPort, sv1Backend.config.ledgerApiUser)
            selectActionAndNavigateToForm("SRARC_SetConfig", "set-dso-config-rules")
            fillAndSubmitProposalForm(
              "set-dso-config-rules",
              rejectedProposalReason,
              requestReasonUrl,
              effectiveAtThreshold = true,
              { implicit webDriver =>
                eventually() {
                  inside(find(testId("config-field-numMemberTrafficContractsThreshold"))) {
                    case Some(element) => element.underlying.sendKeys("42")
                  }
                }
              },
            )
          }
          newVoteRequestWithReason(rejectedProposalReason)
        }

        withFrontEnd("sv1") { implicit webDriver =>
          clue("sv1 sees both proposals in flight and neither in the vote history") {
            go to s"http://localhost:$sv1UIPort/governance"
            eventually() {
              val inflight = rowDescriptions("inflight-proposals")
              inflight should contain(expiringProposalReason)
              inflight should contain(rejectedProposalReason)
              val history = rowDescriptions("vote-history")
              history should not contain expiringProposalReason
              history should not contain rejectedProposalReason
            }
          }
        }

        clue("Resuming vote request expiration automation") {
          sv1Backend.dsoDelegateBasedAutomation.trigger[CloseVoteRequestTrigger].resume()
        }

        withFrontEnd("sv2") { implicit webDriver =>
          sv2CastVoteOnActionRequired(rejectedProposalCid.contractId, accept = false)
        }

        actAndCheck(
          "sv3 and sv4 also reject the proposal",
          Seq(sv3Backend, sv4Backend).foreach(
            _.castVote(rejectedProposalCid, false, requestReasonUrl, "rejecting")
          ),
        )(
          "both proposals are closed, one by vote and one by expiry",
          _ => {
            val closedReasons = sv1Backend
              .listVoteRequestResults(VoteResultsFilters(accepted = Some(false)), 10)
              ._1
              .map(_.request.reason.body)
            closedReasons should contain(rejectedProposalReason)
            closedReasons should contain(expiringProposalReason)
            sv1Backend
              .listVoteRequests()
              .map(_.payload.reason.body)
              .toSet
              .intersect(Set(rejectedProposalReason, expiringProposalReason)) shouldBe empty
          },
        )

        withFrontEnd("sv1") { implicit webDriver =>
          clue("sv1 sees both proposals in the vote history with the right status") {
            go to s"http://localhost:$sv1UIPort/governance"
            eventually() {
              val inflight = rowDescriptions("inflight-proposals")
              inflight should not contain expiringProposalReason
              inflight should not contain rejectedProposalReason
              voteHistoryStatus(rejectedProposalReason) shouldBe Some("Rejected")
              voteHistoryStatus(expiringProposalReason) shouldBe Some("Expired")
            }
          }
        }
    }

    "Vote history is ordered by completion time and supports pagination" in { implicit env =>
      val sv4Party = sv4Backend.getDsoInfo().svParty.toProtoPrimitive
      val offboardSv4Action: ActionRequiringConfirmation = new ARC_DsoRules(
        new SRARC_OffboardSv(new DsoRules_OffboardSv(sv4Party))
      )

      // Create and reject first vote request
      val (_, voteRequest1) = actAndCheck(
        "sv1 creates first vote request",
        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          offboardSv4Action,
          "url",
          "first request",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        ),
      )(
        "first vote request has been created",
        _ =>
          sv1Backend.listVoteRequests().filter(_.payload.reason.body == "first request").loneElement,
      )

      actAndCheck(
        "majority rejects first request",
        Seq(sv1Backend, sv2Backend, sv3Backend).foreach(
          _.castVote(getTrackingId(voteRequest1), false, "url", "reject first")
        ),
      )(
        "first request is rejected",
        _ => {
          sv1Backend
            .listVoteRequests()
            .filter(_.payload.reason.body == "first request") shouldBe empty
          sv1Backend
            .listVoteRequestResults(VoteResultsFilters(accepted = Some(false)), 10)
            ._1
            .exists(_.request.reason.body == "first request") shouldBe true
        },
      )

      // Create and reject second vote request
      val (_, voteRequest2) = actAndCheck(
        "sv1 creates second vote request",
        sv1Backend.createVoteRequest(
          sv1Backend.getDsoInfo().svParty.toProtoPrimitive,
          offboardSv4Action,
          "url",
          "second request",
          sv1Backend.getDsoInfo().dsoRules.payload.config.voteRequestTimeout,
          None,
        ),
      )(
        "second vote request has been created",
        _ =>
          sv1Backend
            .listVoteRequests()
            .filter(_.payload.reason.body == "second request")
            .loneElement,
      )

      actAndCheck(
        "majority rejects second request",
        Seq(sv1Backend, sv2Backend, sv3Backend).foreach(
          _.castVote(getTrackingId(voteRequest2), false, "url", "reject second")
        ),
      )(
        "second request is rejected",
        _ => {
          sv1Backend
            .listVoteRequests()
            .filter(_.payload.reason.body == "second request") shouldBe empty
          sv1Backend
            .listVoteRequestResults(VoteResultsFilters(accepted = Some(false)), 10)
            ._1
            .count(r =>
              r.request.reason.body == "first request" || r.request.reason.body == "second request"
            ) shouldBe 2
        },
      )

      // Verify ordering via backend API: most recently completed first
      clue("vote results are ordered by completion time descending") {
        val (results, _) = sv1Backend.listVoteRequestResults(VoteResultsFilters(), 10)
        val ourResults = results.filter(r =>
          r.request.reason.body == "first request" || r.request.reason.body == "second request"
        )
        ourResults.size shouldBe 2
        ourResults.head.request.reason.body shouldBe "second request"
        ourResults.last.request.reason.body shouldBe "first request"
      }

      // Verify cursor-based pagination via backend API with limit=1
      clue("pagination returns correct pages") {
        val (firstPage, firstPageToken) =
          sv1Backend.listVoteRequestResults(VoteResultsFilters(), 1)
        firstPage.size shouldBe 1
        firstPage.head.request.reason.body shouldBe "second request"
        firstPageToken shouldBe defined

        val (secondPage, _) =
          sv1Backend.listVoteRequestResults(VoteResultsFilters(), 1, firstPageToken)
        secondPage.size shouldBe 1
        secondPage.head.request.reason.body shouldBe "first request"
      }

      // Verify ordering on the governance UI
      withFrontEnd("sv1") { implicit webDriver =>
        loginToGovernance(sv1UIPort, sv1Backend.config.ledgerApiUser)

        eventually() {
          val rows = webDriver.findElements(By.cssSelector("[data-testid='vote-history-row']"))
          rows.size should be >= 2
          val firstRowDescription = rows
            .get(0)
            .findElement(By.cssSelector("[data-testid='vote-history-row-description']"))
            .getText
          val secondRowDescription = rows
            .get(1)
            .findElement(By.cssSelector("[data-testid='vote-history-row-description']"))
            .getText
          // Most recently completed (second request) should appear first
          firstRowDescription shouldBe "second request"
          secondRowDescription shouldBe "first request"
        }
      }
    }
  }

  def getVoteRequestForProposal(
      proposalContractId: String
  )(implicit env: SpliceTestConsoleEnvironment) = {
    val voteRequest: Contract[VoteRequest.ContractId, VoteRequest] = sv1Backend
      .listVoteRequests()
      .find { request =>
        val requestCid = request.contractId.contractId
        val trackingCid =
          if (request.payload.trackingCid.isPresent) {
            Some(request.payload.trackingCid.get.contractId)
          } else {
            None
          }
        requestCid == proposalContractId || trackingCid.contains(proposalContractId)
      }
      .getOrElse(
        fail(
          s"Could not find vote request for proposal contract id: $proposalContractId"
        )
      )
    voteRequest
  }

  def selectMuiOptionByValue(dropdownId: String, optionValue: String)(implicit
      webDriver: WebDriverType
  ): Unit = {
    // Reset potentially stale state and retry the full open+select sequence.
    eventually() {
      webDriver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE)

      val dropdown = webDriver.findElement(By.id(dropdownId))
      dropdown.click()

      val options = webDriver.findElements(By.cssSelector(s"[data-value='$optionValue']"))
      options.size should be > 0
      options.get(0).click()
    }
  }

  def selectFirstMuiOption(dropdownId: String)(implicit webDriver: WebDriverType): Unit = {
    // Reset potentially stale state and retry the full open+select sequence.
    eventually() {
      webDriver.findElement(By.tagName("body")).sendKeys(org.openqa.selenium.Keys.ESCAPE)

      val dropdown = webDriver.findElement(By.id(dropdownId))
      dropdown.getAttribute("aria-disabled") should not be "true"
      dropdown.click()

      val options = webDriver.findElements(By.cssSelector("li[role='option']"))
      options.size should be > 0
      options.get(0).click()
    }
  }

  // This helper is required for filling out form fields with long strings.
  // If those inputs are not chunked, the react throws out warnings with vague messages.
  def fillOutTextField(elementId: String, text: String, chunkSize: Int = 16)(implicit
      webDriver: WebDriverType
  ) = {
    eventually() {
      inside(find(id(elementId))) { case Some(element) =>
        text.grouped(chunkSize).foreach { chunk =>
          element.underlying.sendKeys(chunk)
        }
      }
    }
  }

  def getInflightProposals()(implicit webDriver: WebDriverType) = {
    webDriver.findElements(By.cssSelector("[data-testid='inflight-proposals-row']"))
  }

  def getActionRequiredElems()(implicit webDriver: WebDriverType) = {
    webDriver.findElements(By.cssSelector("[data-testid='action-required-view-details']"))
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
