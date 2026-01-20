package org.lfdecentralizedtrust.splice.integration.tests

import org.lfdecentralizedtrust.splice.codegen.java.splice.wallet.mintingdelegation as mintingDelegationCodegen
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import org.lfdecentralizedtrust.splice.util.{
  SpliceUtil,
  FrontendLoginUtil,
  WalletFrontendTestUtil,
  WalletTestUtil,
}
import com.digitalasset.canton.topology.PartyId
import org.openqa.selenium.WebDriver

import java.time.Duration
import scala.jdk.CollectionConverters.*

class WalletFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("alice")
    with WalletTestUtil
    with WalletFrontendTestUtil
    with FrontendLoginUtil
    with ExternallySignedPartyTestUtil {

  val amuletPrice = 2
  override def walletAmuletPrice = SpliceUtil.damlDecimal(amuletPrice.toDouble)
  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .withAmuletPrice(amuletPrice)

  "A wallet UI" should {

    "tap" should {

      def onboardAndTapTest(damlUser: String)(implicit env: SpliceTestConsoleEnvironment) = {
        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "User logs in", {
              // Do not use browseToWallet below, because that waits for the user to be logged in, which is not the case here
              login(3000, damlUser)
            },
          )(
            "User sees the onboarding page",
            _ => {
              // After a short delay, the UI should realize that the user is not onboarded,
              // and switch to the onbaording page.
              waitForQuery(id("onboard-button"))
            },
          )

          actAndCheck(
            "User onboards themselves", {
              eventuallyClickOn(id("onboard-button"))
            },
          )(
            "User is logged in and onboarded",
            _ => {
              userIsLoggedIn()
              waitForQuery(className("party-id"))
            },
          )

          val testTap = (amountUsd: BigDecimal, feeUpperBoundUsd: BigDecimal) => {

            val amount = walletUsdToAmulet(amountUsd)
            val feeUpperBound = walletUsdToAmulet(feeUpperBoundUsd)

            val (ccTextBefore, usdTextBefore) = eventually() {
              val ccTextBefore = find(id("wallet-balance-amulet")).value.text.trim
              val usdTextBefore = find(id("wallet-balance-usd")).value.text.trim
              ccTextBefore should not be "..."
              usdTextBefore should not be "..."
              (ccTextBefore, usdTextBefore)
            }
            val ccBefore = BigDecimal(ccTextBefore.split(" ").head)
            val usdBefore = BigDecimal(usdTextBefore.split(" ").head)

            actAndCheck(
              s"User taps $amount Amulet in the wallet", {
                tapAmulets(amountUsd)
              },
            )(
              "User sees the updated balance",
              _ => {
                val ccText = find(id("wallet-balance-amulet")).value.text.trim
                val usdText = find(id("wallet-balance-usd")).value.text.trim

                ccText should not be "..."
                usdText should not be "..."
                val cc = BigDecimal(ccText.split(" ").head)
                val usd = BigDecimal(usdText.split(" ").head)

                assertInRange(cc - ccBefore, (amount - feeUpperBound, amount))
                assertInRange(
                  usd - usdBefore,
                  ((amount - feeUpperBound) * amuletPrice, amount * amuletPrice),
                )
              },
            )

          }

          testTap(2, smallAmount)
          testTap(3.14159, 0.05)

        }
      }

      "allow a random user to onboard themselves and show updated balances after tapping" in {
        implicit env =>
          val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
          onboardAndTapTest(aliceDamlUser)
      }

      "allow a random user with uppercase characters to onboard themselves, then tap and list amulets" in {
        implicit env =>
          val damlUser = "UPPERCASE" + aliceWalletClient.config.ledgerApiUser
          onboardAndTapTest(damlUser)
      }

      "fail when trying to use more than 10 decimal points" in { implicit env =>
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        val manyDigits = "1.19191919191919199191"

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice taps balance with more than 10 decimal places in the wallet", {
              browseToAliceWallet(aliceDamlUser)
              eventuallyClickOn(id("tap-amount-field"))
              numberField("tap-amount-field").value = manyDigits
              eventuallyClickOn(id("tap-button"))
            },
          )(
            "Alice has unchanged balance and sees error message",
            _ => {
              import WalletFrontendTestUtil.*
              val ccText = find(id("wallet-balance-amulet")).value.text.trim
              val usdText = find(id("wallet-balance-usd")).value.text.trim
              val errorMessage = find(className(errorDisplayElementClass)).value.text.trim

              ccText should not be "..."
              usdText should not be "..."
              errorMessage should be("Tap operation failed")
              find(className(errorDetailsElementClass)).value.text.trim should
                include(
                  "Failed to decode: Invalid Decimal string \\\"NaN\\\", as it does not match"
                )

              val cc = BigDecimal(ccText.split(" ").head)
              val usd = BigDecimal(usdText.split(" ").head)

              cc shouldBe BigDecimal(0)
              usd shouldBe BigDecimal(0)
            },
          )
        }
      }

    }

    "featured app rights" should {

      "show featured status and support self-featuring" in { implicit env =>
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice logs in", {
              browseToAliceWallet(aliceWalletClient.config.ledgerApiUser)
            },
          )(
            "Alice is initially NOT featured",
            _ => {
              find(id("featured-status")) should be(None)
            },
          )

          actAndCheck(
            "Alice self-features herself", {
              eventuallyClickOn(id("self-feature"))
            },
          )(
            "Alice sees herself as featured",
            _ => {
              find(id("self-feature")) should be(None)
              find(id("featured-status")).valueOrFail("Not featured!")
            },
          )

          actAndCheck(
            "Alice refreshes the page", {
              webDriver.navigate().refresh()
            },
          )(
            "Alice is still featured",
            _ => {
              find(id("self-feature")) should be(None)
              find(id("featured-status")).valueOrFail("Not featured anymore!")
            },
          )
        }

      }

    }

    "with delegations and their proposals" should {

      "allow them to be accepted, rejected or withdrawn as appropriate" in { implicit env =>
        def checkRowCounts(proposalCount: Long, activeCount: Long)(implicit
            webDriver: WebDriver
        ): Unit = {
          val proposalRows = findAll(className("proposal-row")).toSeq
          proposalRows should have size proposalCount
          val delegationRows = findAll(className("delegation-row")).toSeq
          delegationRows should have size activeCount
        }

        // 1. Setup
        val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
        onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
        val aliceParty =
          PartyId.tryFromProtoPrimitive(aliceWalletClient.userStatus().party)

        // Tap to fund the validator wallet for external party setup
        // (external party operations go through aliceValidatorBackend)
        aliceValidatorWalletClient.tap(100.0)

        // Onboard three external parties as beneficiaries
        val beneficiary1Onboarding =
          onboardExternalParty(aliceValidatorBackend, Some("beneficiary1"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary1Onboarding)

        val beneficiary2Onboarding =
          onboardExternalParty(aliceValidatorBackend, Some("beneficiary2"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary2Onboarding)

        val beneficiary3Onboarding =
          onboardExternalParty(aliceValidatorBackend, Some("beneficiary3"))
        createAndAcceptExternalPartySetupProposal(aliceValidatorBackend, beneficiary3Onboarding)

        // 2. Verify empty initial state via API
        clue("Check that no minting delegation proposals exist initially") {
          aliceWalletClient.listMintingDelegationProposals().proposals shouldBe empty
        }
        clue("Check that no minting delegations exist initially") {
          aliceWalletClient.listMintingDelegations().delegations shouldBe empty
        }

        // 3. Create three proposals, one from each beneficiary
        val envNow = env.environment.clock.now
        val expiresAt = envNow.plus(Duration.ofDays(30)).toInstant
        val expiresDayAfter = envNow.plus(Duration.ofDays(31)).toInstant
        actAndCheck(
          "Each beneficiary creates a minting delegation proposal", {
            createMintingDelegationProposal(beneficiary1Onboarding, aliceParty, expiresAt)
            createMintingDelegationProposal(beneficiary2Onboarding, aliceParty, expiresAt)
            createMintingDelegationProposal(beneficiary3Onboarding, aliceParty, expiresDayAfter)
          },
        )(
          "and they are successfully created",
          _ => {
            aliceWalletClient
              .listMintingDelegationProposals()
              .proposals should have size 3
          },
        )

        // 4. Test via Selenium UI (using Alice's wallet frontend)
        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "Alice browses to the wallet", {
              browseToAliceWallet(aliceDamlUser)
            },
          )(
            "Alice sees the Delegations tab",
            _ => {
              waitForQuery(id("navlink-delegations"))
            },
          )

          actAndCheck(
            "Alice clicks on Delegations tab", {
              eventuallyClickOn(id("navlink-delegations"))
            },
          )(
            "Alice sees the Proposed table with 3 proposals and empty Delegations table",
            _ => {
              find(id("proposals-label")).valueOrFail("Proposed heading not found!")
              val proposalRows = findAll(className("proposal-row")).toSeq
              proposalRows should have size 3

              proposalRows.foreach { row =>
                row
                  .findChildElement(className("proposal-accept"))
                  .valueOrFail("Accept button not found in proposal row!")
                row
                  .findChildElement(className("proposal-reject"))
                  .valueOrFail("Reject button not found in proposal row!")
              }

              find(id("delegations-label")).valueOrFail("Delegations heading not found!")
              find(id("no-delegations-message")).valueOrFail("No delegations message not found!")
            },
          )

          // 5. Accept first proposal via UI
          actAndCheck(
            "Alice clicks Accept on the first proposal and confirms", {
              clickByCssSelector(".proposal-row .proposal-accept")
              eventuallyClickOn(id("accept-proposal-confirmation-dialog-accept-button"))
            },
          )(
            "2 proposals remain, 1 delegation created",
            _ => {
              eventually() {
                checkRowCounts(2, 1)
              }
            },
          )

          // 6. Accept second proposal via UI
          actAndCheck(
            "Alice clicks Accept on the second proposal and confirms", {
              clickByCssSelector(".proposal-row .proposal-accept")
              eventuallyClickOn(id("accept-proposal-confirmation-dialog-accept-button"))
            },
          )(
            "1 proposal remains, 2 delegations exist",
            _ => {
              eventually() {
                checkRowCounts(1, 2)
              }
            },
          )

          // 7. Withdraw one delegation via UI
          actAndCheck(
            "Alice clicks Withdraw on the first delegation and confirms", {
              clickByCssSelector(".delegation-row .delegation-withdraw")
              eventuallyClickOn(id("withdraw-delegation-confirmation-dialog-accept-button"))
            },
          )(
            "1 proposal remains, 1 delegation remains",
            _ => {
              eventually() {
                checkRowCounts(1, 1)
              }
            },
          )

          // 8. Reject the final proposal via UI
          actAndCheck(
            "Alice clicks Reject on the final proposal and confirms", {
              clickByCssSelector(".proposal-row .proposal-reject")
              eventuallyClickOn(id("reject-proposal-confirmation-dialog-accept-button"))
            },
          )(
            "No proposals remain, 1 delegation remains",
            _ => {
              eventually() {
                find(id("no-proposals-message")).valueOrFail("No proposals message not found!")
                checkRowCounts(0, 1)
              }
            },
          )

          // 9. Create two proposals, from beneficiaries that have not completedly onboarding
          val beneficiary4Incomplete =
            onboardExternalParty(aliceValidatorBackend, Some("beneficiary4"))
          val beneficiary5Incomplete =
            onboardExternalParty(aliceValidatorBackend, Some("beneficiary5"))
          actAndCheck(
            "Each beneficiary creates a minting delegation proposal", {
              createMintingDelegationProposal(beneficiary4Incomplete, aliceParty, expiresDayAfter)
              createMintingDelegationProposal(beneficiary5Incomplete, aliceParty, expiresDayAfter)
              webDriver.navigate().refresh()
            },
          )(
            "2 new proposals appear, 1 delegation remains",
            _ => {
              eventually() {
                checkRowCounts(2, 1)
              }
            },
          )

          actAndCheck(
            "Accept a non-onboarded proposal via api, refresh the UI", {
              val proposals = aliceWalletClient.listMintingDelegationProposals()
              val cid = proposals.proposals.head.contract.contractId
              aliceWalletClient.acceptMintingDelegationProposal(cid)
              webDriver.navigate().refresh()
            },
          )(
            "1 proposal remain, 2 delegations are visible",
            _ => {
              eventually() {
                checkRowCounts(1, 2)
              }
            },
          )

          // 10. Add another proposal, refresh the UI and confirm that it appears
          actAndCheck(
            "Beneficiary 2 creates new minting proposal and UI refreshes", {
              createLimitedMintingDelegationProposal(
                beneficiary2Onboarding,
                aliceParty,
                expiresAt,
                18,
              )
              webDriver.navigate().refresh()
            },
          )(
            "1 new proposal appears making 2, 2 delegations remain",
            _ => {
              eventually() {
                aliceWalletClient
                  .listMintingDelegationProposals()
                  .proposals should have size 2
                checkRowCounts(2, 2)
              }
            },
          )

          // 11. Accept proposal that causes automatic withdraw of existing delegation for beneficary 2
          actAndCheck(
            "Alice clicks Accept on new proposal and confirms", {
              clickByCssSelector(".proposal-row .proposal-accept")
              eventuallyClickOn(id("accept-proposal-confirmation-dialog-accept-button"))
            },
          )(
            "1 proposal and 2 delegations remain",
            _ => {
              eventually() {
                checkRowCounts(1, 2)
              }
            },
          )
        }
      }

    }

    "show logged in ANS name" in { implicit env =>
      // Create directory entry for alice
      val aliceDamlUser = aliceWalletClient.config.ledgerApiUser
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val entryName = perTestCaseName("alice")

      createAnsEntry(
        aliceAnsExternalClient,
        entryName,
        aliceWalletClient,
      )
      eventuallySucceeds() {
        sv1ScanBackend.lookupEntryByName(entryName)
      }

      withFrontEnd("alice") { implicit webDriver =>
        actAndCheck(
          "Alice browses to the wallet", {
            browseToAliceWallet(aliceDamlUser)
          },
        )(
          "Alice sees her Name Service entry name",
          _ => {
            seleniumText(find(id("logged-in-user"))) should matchText(entryName)
          },
        )

        actAndCheck(
          "Alice refreshes the page", {
            webDriver.navigate().refresh()
          },
        )(
          "The name is still there",
          _ => {
            seleniumText(find(id("logged-in-user"))) should matchText(entryName)
          },
        )
      }
    }
  }

  private def createMintingDelegationProposal(
      beneficiaryOnboarding: OnboardingResult,
      delegate: PartyId,
      expiresAt: java.time.Instant,
  )(implicit env: SpliceTestConsoleEnvironment): Unit =
    createLimitedMintingDelegationProposal(beneficiaryOnboarding, delegate, expiresAt, 10)

  private def createLimitedMintingDelegationProposal(
      beneficiaryOnboarding: OnboardingResult,
      delegate: PartyId,
      expiresAt: java.time.Instant,
      mergeLimit: Int,
  )(implicit env: SpliceTestConsoleEnvironment): Unit = {
    val beneficiary = beneficiaryOnboarding.party
    val proposal = new mintingDelegationCodegen.MintingDelegationProposal(
      new mintingDelegationCodegen.MintingDelegation(
        beneficiary.toProtoPrimitive,
        delegate.toProtoPrimitive,
        dsoParty.toProtoPrimitive,
        expiresAt,
        mergeLimit,
      )
    )
    // Use externally signed submission for the external party
    aliceValidatorBackend.participantClientWithAdminToken.ledger_api_extensions.commands
      .submitJavaExternalOrLocal(
        actingParty = beneficiaryOnboarding.richPartyId,
        commands = proposal.create.commands.asScala.toSeq,
      )
  }
}
