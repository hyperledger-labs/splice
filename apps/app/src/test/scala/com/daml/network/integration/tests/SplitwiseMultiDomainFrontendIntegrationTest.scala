package com.daml.network.integration.tests

import com.daml.network.config.CoinConfigTransforms
import com.daml.network.environment.CoinEnvironmentImpl
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.integration.tests.CoinTests.{CoinTestConsoleEnvironment}
import com.daml.network.util.{DirectoryTestUtil, WalletTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import org.openqa.selenium.Keys

class SplitwiseMultiDomainFrontendIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment(
      "aliceSplitwise",
      "bobSplitwise",
    )
    with DirectoryTestUtil
    with WalletTestUtil {

  private val splitwiseDarPath = "daml/splitwise/.daml/dist/splitwise-0.1.0.dar"
  private val directoryDarPath = "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition
      : BaseEnvironmentDefinition[CoinEnvironmentImpl, CoinTestConsoleEnvironment] =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransform((_, config) => CoinConfigTransforms.useSeparateSplitwiseDomain()(config))
      .withAdditionalSetup(implicit env => {
        Seq(splitwiseDarPath, directoryDarPath).foreach { path =>
          aliceValidator.remoteParticipant.dars.upload(path)
          bobValidator.remoteParticipant.dars.upload(path)
        }
      })

  "splitwise" should {
    "go through install & payment flow on private domain" in { implicit env =>
      val aliceDamlUser = aliceSplitwise.config.ledgerApiUser
      val aliceUserParty = onboardWalletUser(aliceWallet, aliceValidator)
      val bobDamlUser = bobSplitwise.config.ledgerApiUser
      val bobUserParty = onboardWalletUser(bobWallet, bobValidator)

      aliceWallet.tap(50)

      val aliceEntryName = perTestCaseName("alice.cns")
      val bobEntryName = perTestCaseName("bob.cns")
      initialiseDirectoryApp(aliceEntryName, aliceUserParty, aliceDirectory, aliceWallet)
      initialiseDirectoryApp(bobEntryName, bobUserParty, bobDirectory, bobWallet)
      val aliceCns = expectedCns(aliceUserParty, aliceEntryName)
      val bobCns = expectedCns(bobUserParty, bobEntryName)

      val groupId = "alice_group"

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        go to "http://localhost:3002"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on "group-id-field"
        textField("group-id-field").value = groupId
        click on "create-group-button"
        click on className("create-invite-link")
      }

      withFrontEnd("bobSplitwise") { implicit webDriver =>
        go to "http://localhost:3003"
        click on "user-id-field"
        textField("user-id-field").value = bobDamlUser
        click on "login-button"
        eventually() {
          findAll(className("request-membership-link")).toSeq should have length 1
        }
        click on className("request-membership-link")
      }

      withFrontEnd("aliceSplitwise") { implicit webDriver =>
        click on className("add-user-link")
        inside(find(className("transfer-amount-field"))) { case Some(field) =>
          field.underlying.click()
          reactTextInput(field).value = "42"
        }
        inside(find(className("transfer-receiver-field"))) { case Some(field) =>
          field.underlying.click()
          val input = reactTextInput(field)
          input.underlying.sendKeys(bobEntryName)
          input.underlying.sendKeys(Keys.ARROW_DOWN)
          input.underlying.sendKeys(Keys.ENTER)
        }
        click on className("transfer-link")

        // Alice is redirected to wallet ..
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on className("accept-button")

        // And then back to splitwise, where she is already logged in
        eventually() {
          inside(findAll(className("balance-updates-list-item")).toSeq) { case Seq(row) =>
            row.text should matchText(s"${aliceCns} sent 42.0000000000 CC to ${bobCns}")
          }
        }
      }

    }
  }
}
