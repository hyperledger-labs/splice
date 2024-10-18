package org.lfdecentralizedtrust.splice.integration.tests

import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import cats.syntax.either.*
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AppConfiguration,
  ReleaseConfiguration,
  Timespan,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.util.{FrontendLoginUtil, AnsFrontendTestUtil, WalletTestUtil}

import java.io.File
import scala.util.Try

class AppManagerFrontendIntegrationTest
    extends FrontendIntegrationTest("splitwell", "alice")
    with WalletTestUtil
    with AnsFrontendTestUtil
    with FrontendLoginUtil {

  private val splitwellBundle = new File(
    "apps/splitwell/src/test/resources/splitwell-bundle-1.0.0.tar.gz"
  )

  private val splitwellBundleUpgrade = new File(
    "apps/splitwell/src/test/resources/splitwell-bundle-2.0.0.tar.gz"
  )

  private def splitwellBundleEntity = HttpEntity.fromFile(
    ContentTypes.`application/octet-stream`,
    splitwellBundle,
  )

  override def environmentDefinition =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms((_, config) =>
        ConfigTransforms.disableSplitwellUserDomainConnections(config)
      )
      .withoutInitialManagerApps

  "app manager" should {

    "register, install and launch splitwell" in { implicit env =>
      val installLink = withFrontEnd("splitwell") { implicit webDriver =>
        // login to wallet UI once to create saved localstorage auth session
        login(splitwellAppManagerUIPort, splitwellValidatorBackend.config.ledgerApiUser)
        textField(className("app-config-name-input")).underlying.sendKeys("splitwell")
        textField(className("app-config-ui-url-input")).underlying
          .sendKeys(s"http://localhost:$splitwellSplitwellUIPort")
        textField(className("app-config-allowed-redirect-uris")).underlying
          .sendKeys(
            s"http://localhost:$splitwellSplitwellUIPort,http://localhost:$splitwellSplitwellUIPort/another"
          )
        click on className("add-release-configuration")
        textField(className("release-config-release-version-input")).underlying.sendKeys("1.0.0")
        click on className("release-config-add-domain-button")
        textField(className("domain-alias-input")).underlying.sendKeys("splitwell")
        textField(className("domain-url-input")).underlying
          .sendKeys("http://localhost:5708")
        find(className("register-app-release-bundle-input")).value.underlying
          .sendKeys(splitwellBundle.getAbsolutePath)
        textField(id("register-app-provider-user-input")).value =
          splitwellBackend.config.providerUser
        val (_, link) =
          actAndCheck("Click on register app button", click on id("register-app-button"))(
            "App appears in listed apps",
            _ =>
              inside(findAll(className("registered-app")).toSeq) { case Seq(splitwell) =>
                splitwell.childElement(className("registered-app-name")).text shouldBe "splitwell"
                splitwell.childElement(className("registered-app-link")).attribute("href").value
              },
          )
        link
      }
      withFrontEnd("alice") { implicit webDriver =>
        login(aliceAppManagerUIPort, aliceValidatorBackend.config.ledgerApiUser)
        textField(id("install-app-input")).value = installLink
        val (_, splitwell) =
          actAndCheck("Click on install app button", click on id("install-app-button"))(
            "App appears in installed apps",
            _ =>
              inside(findAll(className("installed-app")).toSeq) { case Seq(splitwell) =>
                splitwell.childElement(className("installed-app-name")).text shouldBe "splitwell"
                splitwell
              },
          )
        splitwell
          .findAllChildElements(className("unapproved-release-configuration"))
          .toSeq should have size 1
        splitwell
          .findAllChildElements(className("approved-release-configuration"))
          .toSeq should have size 0
        actAndCheck(
          "approve release configuration",
          click on splitwell.childElement(className("approve-release-configuration-button")),
        )(
          "release configuration is approved",
          _ => {
            splitwell
              .findAllChildElements(className("unapproved-release-configuration"))
              .toSeq should have size 0
            splitwell
              .findAllChildElements(className("approved-release-configuration"))
              .toSeq should have size 1
          },
        )
        actAndCheck(
          "Launch app",
          click on splitwell.childElement(className("installed-app-link")),
        )(
          "splitwell UI shows up",
          _ =>
            // This also implies the install contract has been created
            // which means we can properly do writes through the user’s participant.
            find(id("create-group-button")) should not be empty,
        )
      }
    }
    "publish new app releases and update configurations" in { implicit env =>
      val provider = splitwellBackend.getProviderPartyId()
      val configuration1_0 = AppConfiguration(
        0L,
        "splitwell",
        s"http://localhost:$splitwellSplitwellUIPort",
        Vector(s"http://localhost:$splitwellSplitwellUIPort"),
        Vector(
          ReleaseConfiguration(
            domains = Vector.empty,
            releaseVersion = "1.0.0",
            requiredFor = Timespan(),
          )
        ),
      )
      splitwellValidatorBackend.registerApp(
        splitwellBackend.config.providerUser,
        configuration1_0,
        splitwellBundleEntity,
      )
      assertThrowsAndLogsCommandFailures(
        splitwellValidatorBackend.getAppRelease(provider, "2.0.0").version shouldBe "2.0.0",
        _.errorMessage should include("No release 2.0.0 found"),
      )
      splitwellValidatorBackend.getLatestAppConfiguration(provider).version shouldBe 0
      withFrontEnd("splitwell") { implicit webDriver =>
        login(splitwellAppManagerUIPort, splitwellValidatorBackend.config.ledgerApiUser)
        val splitwell = find(className("registered-app")).value
        splitwell
          .childElement(className("registered-app-release-bundle-input"))
          .underlying
          .sendKeys(splitwellBundleUpgrade.getAbsolutePath)
        actAndCheck(
          "click on publish app release button",
          click on splitwell.childElement(className("registered-app-publish-release-button")),
        )(
          "release is uploaded",
          _ =>
            Try(
              loggerFactory.suppressErrors(
                splitwellValidatorBackend.getAppRelease(provider, "2.0.0").version shouldBe "2.0.0"
              )
            ).toEither.valueOr(fail(_)),
        )
        click on splitwell.childElement(className("registered-app-edit-configuration-button"))
        inside(splitwell.childElement(className("release-config-release-version-input"))) {
          case textField: TextField => textField.value = "2.0.0"
        }
        actAndCheck(
          "click on update app configuration",
          click on splitwell.childElement(className("registered-app-update-configuration-button")),
        )(
          "configuration is updated",
          _ => {
            val latestConfig = splitwellValidatorBackend.getLatestAppConfiguration(provider)
            latestConfig.version shouldBe 1
            latestConfig.releaseConfigurations.loneElement.releaseVersion shouldBe "2.0.0"
          },
        )
      }
    }
  }
}
