package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.codegen.java.cn.{directory as dirCodegen, splitwise as splitwiseCodegen}
import com.daml.network.console.{RemoteDirectoryAppReference, WalletAppClientReference}
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.CoinTestConsoleEnvironment
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import com.digitalasset.canton.util.ShowUtil.*
import org.openqa.selenium.Keys

import scala.concurrent.duration.DurationInt
import scala.util.Using

class SplitwiseFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvirontment(
      "aliceSplitwise",
      "bobSplitwise",
      "charlieSplitwise",
    )
    with WalletTestUtil {

  private val splitwiseDarPath = "daml/splitwise/.daml/dist/splitwise-0.1.0.dar"
  private val directoryDarPath = "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        CoinEnvironmentDefinition.simpleTopology(this.getClass.getSimpleName).setup(env)
        Seq(splitwiseDarPath, directoryDarPath).foreach { path =>
          aliceValidator.remoteParticipant.dars.upload(path)
          bobValidator.remoteParticipant.dars.upload(path)
        }
      })

  def initialiseDirectoryApp(
      userName: String,
      userParty: PartyId,
      directory: RemoteDirectoryAppReference,
      wallet: WalletAppClientReference,
  ): Unit = {
    actAndCheck("Request directory install", directory.requestDirectoryInstall())(
      "Install created",
      _ =>
        directory.ledgerApi.ledger_api.acs
          .awaitJava(dirCodegen.DirectoryInstall.COMPANION)(userParty),
    )

    val (_, reqId) = actAndCheck(
      show"Request directory entry ${userName.singleQuoted} for $userParty",
      directory.requestDirectoryEntry(userName),
    )(
      "There is exactly one subscription request",
      _ => {
        val reqs = wallet.listSubscriptionRequests()
        reqs should have length 1
        reqs.head.contractId
      },
    )

    actAndCheck(
      "Tap and accept subscription request", {
        wallet.tap(5.0)
        wallet.acceptSubscriptionRequest(reqId)
      },
    )(
      "There are no subscription request left",
      _ => wallet.listSubscriptionRequests() should have length 0,
    )
  }

  /** The `<TextInput>` in ts code is converted by react into a deep tree. This returns the input field. */
  private def reactTextInput(textField: Element): TextField = new TextField(
    textField.childElement(className("MuiInputBase-input")).underlying
  )

  "A splitwise UI" should {

    "settle debts with multiple parties" in { implicit env =>
      val aliceDamlUser = aliceSplitwise.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobDamlUser = bobSplitwise.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val charlieDamlUser = charlieSplitwise.config.ledgerApiUser
      // we re-use alice's validator here to save some resources
      val charlieValidator = aliceValidator
      val charlieUserParty = onboardWalletUser(charlieWallet, charlieValidator)
      val groupName = "troika"

      val aliceEntryName = perTestCaseName("alice.cns")
      val bobEntryName = perTestCaseName("bob.cns")
      val charlieEntryName = perTestCaseName("charlie.cns")
      initialiseDirectoryApp(aliceEntryName, aliceUserParty, aliceDirectory, aliceWallet)
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectory, bobWallet)
      initialiseDirectoryApp(charlieEntryName, charlieUserParty, charlieDirectory, charlieWallet)
      val aliceCns = expectedCns(aliceUserParty, aliceEntryName)
      val bobCns = expectedCns(bobUserParty, bobEntryName)
      val charlieCns = expectedCns(charlieUserParty, charlieEntryName)

      bobWallet.tap(550)

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        go to "http://localhost:3002"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "group-id-field"
        textField("group-id-field").value = groupName
        click on "create-group-button"
        click on className("create-invite-link")
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        go to "http://localhost:3003"
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(bobUserParty)
        click on className("request-membership-link")
      }

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        click on className("add-user-link")
      }

      withFrontEnd("charlieSplitwise") { implicit webDriver =>
        go to "http://localhost:3005"
        click on "user-id-field"
        textField("user-id-field").value = charlieDamlUser
        click on "login-button"
        charlieValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(charlieUserParty)
        click on className("request-membership-link")
      }

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        click on className("add-user-link")

        inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "1200.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Team lunch"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("charlieSplitwise") { implicit webDriver =>
        inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "333.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Digestivs"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) {
            case Seq(r1, r2) => // Need to sync here on the actual values (size not enough)
              r1.childElement(className("balances-table-receiver")).text should matchText(
                aliceCns
              )
              r1.childElement(className("balances-table-amount")).text shouldBe "-400.0000000000"
              r2.childElement(className("balances-table-receiver")).text should matchText(
                charlieCns
              )
              r2.childElement(className("balances-table-amount")).text shouldBe "-111.0000000000"
          }
        }
        click on className("settle-my-debts-link")

        // Bob is redirected to wallet ..
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"

        click on className("accept-button")

        // And then back to splitwise, where he is already logged in
        eventually() {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row1, row2) =>
            row1.childElement(className("balances-table-receiver")).text should matchText(
              aliceCns
            )
            row1.childElement(className("balances-table-amount")).text shouldBe "0.0000000000"
            row2.childElement(className("balances-table-receiver")).text should matchText(
              charlieCns
            )
            row2.childElement(className("balances-table-amount")).text shouldBe "0.0000000000"
          }
          inside(findAll(className("balance-updates-list-item")).toSeq) {
            case Seq(row1, row2, row3, row4) =>
              row1.text should matchText(s"${bobCns} sent 111.0000000000 CC to ${charlieCns}")
              row2.text should matchText(s"${bobCns} sent 400.0000000000 CC to ${aliceCns}")
              row3.text should matchText(s"${charlieCns} paid 333.0000000000 CC for Digestivs")
              row4.text should matchText(s"${aliceCns} paid 1200.0000000000 CC for Team lunch")
          }
        }
      }

      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceWallet, Seq((403.75, 404)))
        checkWallet(bobUserParty, bobWallet, Seq((40.3, 40.5)))
        checkWallet(charlieUserParty, charlieWallet, Seq((114.75, 115)))
      }
    }

    "settle debts with a single party" in { implicit env =>
      val aliceDamlUser = aliceSplitwise.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobDamlUser = bobSplitwise.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val groupName = "troika"

      val aliceEntryName = perTestCaseName("alice.cns")
      val bobEntryName = perTestCaseName("bob.cns")
      initialiseDirectoryApp(aliceEntryName, aliceUserParty, aliceDirectory, aliceWallet)
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectory, bobWallet)
      val aliceCns = expectedCns(aliceUserParty, aliceEntryName)
      val bobCns = expectedCns(bobUserParty, bobEntryName)
      bobWallet.tap(510)

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        go to "http://localhost:3002"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "group-id-field"
        textField("group-id-field").value = groupName
        click on "create-group-button"
        click on className("create-invite-link")
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        go to "http://localhost:3003"
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(bobUserParty)
        click on className("request-membership-link")
      }

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        click on className("add-user-link")
        inside(find(className("enter-payment-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "1000.0"
        }
        inside(find(className("enter-payment-description-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "Team lunch"
        }
        click on className("enter-payment-link")
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        inside(find(className("transfer-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "500"
        }
        inside(find(className("transfer-receiver-field"))) { case Some(field) =>
          field.underlying.click()
          val input = reactTextInput(field)
          // TODO(#2206) change aliceEntryName back to "alice" for better UX testing
          input.underlying.sendKeys(aliceEntryName)
          input.underlying.sendKeys(Keys.ARROW_DOWN)
          input.underlying.sendKeys(Keys.ENTER)
        }
        click on className("transfer-link")

        // Bob is redirected to wallet ..
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"

        click on className("accept-button")

        // And then back to splitwise, where he is already logged in
        eventually(scaled(5 seconds)) {
          inside(findAll(className("balances-table-row")).toSeq) { case Seq(row) =>
            row.childElement(className("balances-table-receiver")).text should matchText(aliceCns)
            row.childElement(className("balances-table-amount")).text.toDouble shouldBe 0.0
          }
          inside(findAll(className("balance-updates-list-item")).toSeq.sortBy(_.text)) {
            case Seq(row1, row2) =>
              row1.text should matchText(s"${aliceCns} paid 1000.0000000000 CC for Team lunch")
              row2.text should matchText(s"${bobCns} sent 500.0000000000 CC to ${aliceCns}")
          }
        }
      }

      eventually() {
        // Check final amounts in the wallets
        checkWallet(aliceUserParty, aliceWallet, Seq((503.75, 504)))
        checkWallet(bobUserParty, bobWallet, Seq((12.3, 12.5)))
      }
    }

    "handle multiple groups correctly" in { implicit env =>
      val aliceDamlUser = aliceSplitwise.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobDamlUser = bobSplitwise.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)
      val charlieDamlUser = charlieSplitwise.config.ledgerApiUser
      // we re-use alice's validator here to save some resources
      val charlieValidator = aliceValidator
      val charlieUserParty = onboardWalletUser(charlieWallet, charlieValidator)

      val aliceEntryName = perTestCaseName("alice.cns")
      val bobEntryName = perTestCaseName("bob.cns")
      val charlieEntryName = perTestCaseName("charlie.cns")
      initialiseDirectoryApp(aliceEntryName, aliceUserParty, aliceDirectory, aliceWallet)
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectory, bobWallet)
      initialiseDirectoryApp(charlieEntryName, charlieUserParty, charlieDirectory, charlieWallet)

      // Alice creates three groups - abc, ab, ac
      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        go to "http://localhost:3002"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"

        click on "group-id-field"
        textField("group-id-field").value = "group-abc"
        click on "create-group-button"

        click on "group-id-field"
        textField("group-id-field").value = "group-ab"
        click on "create-group-button"

        click on "group-id-field"
        textField("group-id-field").value = "group-ac"
        click on "create-group-button"

        eventually() {
          findAll(className("create-invite-link")).toSeq should have length 3
        }
        findAll(className("create-invite-link")).toSeq.map(click on _)
      }

      // Bob requests to join groups abc and ab
      withFrontEnd("bobSplitwise") { implicit webDriver =>
        go to "http://localhost:3003"
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(bobUserParty)
        eventually() {
          findAll(className("request-membership-link")).toSeq should have length 3
        }
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-abc"))
          .head
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-ab"))
          .head
      }

      // Charlie requests to join groups abc and ac
      withFrontEnd("charlieSplitwise") { implicit webDriver =>
        go to "http://localhost:3005"
        click on "user-id-field"
        textField("user-id-field").value = charlieDamlUser
        click on "login-button"
        bobValidator.remoteParticipantWithAdminToken.ledger_api.acs
          .awaitJava(splitwiseCodegen.GroupInvite.COMPANION)(bobUserParty)
        eventually() {
          findAll(className("request-membership-link")).toSeq should have length 3
        }
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-abc"))
          .head
        click on findAll(className("request-membership-link")).toSeq
          .filter(_.attribute("data-owner") == Some(aliceUserParty.toProtoPrimitive))
          .filter(_.attribute("data-group") == Some("group-ac"))
          .head
      }

      // Alice accepts all requests
      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        eventually(timeUntilSuccess = 20.minute) {
          findAll(className("add-user-link")) should have length 4
        }
        val allLinks = findAll(className("add-user-link")).toSeq
        (allLinks zip (4L to 1 by -1)).foreach { case (elem, i) =>
          // Wait for the previous join to finish. Otherwise we get contention on the group contract.
          eventually() {
            findAll(className("add-user-link")) should have length i
          }
          click on elem
        }
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        eventually() {
          findAll(className("group-entry")) should have length 2
          val groups = findAll(className("group-entry")).toSeq
            .map(elem => (elem.attribute("data-group-owner"), elem.attribute("data-group-id")))
            .sorted
          groups should be(
            Seq(
              (Some(aliceUserParty.toProtoPrimitive), Some("group-ab")),
              (Some(aliceUserParty.toProtoPrimitive), Some("group-abc")),
            )
          )
        }
      }
      withFrontEnd("charlieSplitwise") { implicit webDriver =>
        eventually() {
          findAll(className("group-entry")) should have length 2
          val groups = findAll(className("group-entry")).toSeq
            .map(elem => (elem.attribute("data-group-owner"), elem.attribute("data-group-id")))
            .sorted
          groups should be(
            Seq(
              (Some(aliceUserParty.toProtoPrimitive), Some("group-abc")),
              (Some(aliceUserParty.toProtoPrimitive), Some("group-ac")),
            )
          )
        }
      }
    }

    "allow login via auth0" taggedAs LocalAuth0Test in { implicit env =>
      val auth0 = auth0UtilFromEnvVars("https://canton-network-test.us.auth0.com")
      Using.resource(retryAuth0Calls(auth0.createUser())) { user =>
        logger.debug(s"Created user ${user.email} with password ${user.password} (id: ${user.id})")
        val userPartyId = aliceValidator.onboardUser(user.id)

        withFrontEnd("aliceSplitwise") { implicit webDriver =>
          actAndCheck(
            "The user logs in with OAauth2 and completes all Auth0 login prompts", {
              go to "http://localhost:3005"
              click on "oidc-login-button"
              completeAuth0LoginWithAuthorization(user.email, user.password)
            },
          )(
            "The user sees his own party ID in the app",
            _ =>
              find(id("logged-in-user")).value.text should matchText(userPartyId.toProtoPrimitive),
          )
        }
      }
    }
  }
}
