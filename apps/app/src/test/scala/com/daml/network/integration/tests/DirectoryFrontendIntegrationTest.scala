package com.daml.network.integration.tests

import com.daml.network.LocalAuth0Test
import com.daml.network.integration.CoinEnvironmentDefinition
import com.daml.network.util.WalletTestUtil

import scala.concurrent.duration.DurationInt
import scala.util.Using

class DirectoryFrontendIntegrationTest
    extends FrontendIntegrationTest("alice")
    with WalletTestUtil {

  private val directoryDarPath =
    "daml/directory-service/.daml/dist/directory-service-0.1.0.dar"

  override def environmentDefinition =
    CoinEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .withAdditionalSetup(implicit env => {
        aliceValidator.remoteParticipant.dars.upload(directoryDarPath)
      })

  "A directory UI" should {

    "allow requesting an entry with subscription payments and then list it" in { implicit env =>
      val aliceDamlUser = aliceWallet.config.damlUser
      onboardWalletUser(aliceWallet, aliceValidator)
      aliceWallet.tap(100.0)

      val entryName = "mycoolentry"

      aliceWallet.listSubscriptionRequests() shouldBe empty

      withFrontEnd("alice") { implicit webDriver =>
        go to "http://localhost:3004"
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        eventually(scaled(10 seconds)) {
          click on "entry-name-field"
        }
        textField("entry-name-field").value = entryName
        click on "request-entry-with-sub-button"

        // Alice is redirected to wallet...
        click on "user-id-field"
        textField("user-id-field").value = aliceDamlUser
        click on "login-button"
        click on className("sub-request-accept-button")

        // And then back to directory, where she is already logged in
        eventually(scaled(10 seconds)) {
          findAll(className("entries-table-row")) should have size 1
        }
        val row: Element = inside(findAll(className("entries-table-row")).toList) { case Seq(row) =>
          row
        }
        val name = row.childElement(className("entries-table-name"))
        name.text should be(entryName)
      }
    }

    "allow login via auth0" taggedAs LocalAuth0Test in { implicit env =>
      val auth0 = auth0UtilFromEnvVars("https://canton-network-test.us.auth0.com")
      Using.resource(auth0.createUser()) { user =>
        logger.debug(s"Created user ${user.email} with password ${user.password} (id: ${user.id})")
        val userPartyId = aliceValidator.onboardUser(user.id)

        withFrontEnd("alice") { implicit webDriver =>
          actAndCheck(
            "The user logs in with OAauth2 and completes all Auth0 login prompts", {
              go to "http://localhost:3004"
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
