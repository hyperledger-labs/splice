package com.daml.network.integration.tests

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Uri}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions.{InstalledApp, RegisteredApp}
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTestWithSharedEnvironment,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.google.protobuf.ByteString

import java.io.{File, FileInputStream}

class AppManagerIntegrationTest
    extends CNNodeIntegrationTestWithSharedEnvironment
    with WalletTestUtil {

  private val splitwellBundle = new File(
    "apps/splitwell/src/test/resources/splitwell-bundle.tar.gz"
  )

  private def splitwellBundleEntity = HttpEntity.fromFile(
    ContentTypes.`application/octet-stream`,
    splitwellBundle,
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms(
        CNNodeConfigTransforms.onlySv1,
        (_, config) => CNNodeConfigTransforms.disableSplitwellUserDomainConnections(config),
      )
      .withAdditionalSetup(implicit env =>
        // Shared integration test so we upload here rather than within the tests.
        splitwellValidatorBackend.registerApp(
          splitwellBundleEntity
        )
      )

  "app manager" should {
    "support app registration" in { implicit env =>
      splitwellValidatorBackend.listRegisteredApps() shouldBe Seq(
        RegisteredApp(
          "splitwell",
          splitwellValidatorBackend.config.appManager.value.appManagerApiUrl
            .withPath(
              Uri.Path("/app-manager/apps/registered/splitwell/manifest.json")
            )
            .toString,
        )
      )
      splitwellValidatorBackend.getAppManifest("splitwell").name shouldBe "splitwell"
      val uploadedBundle = ByteString.readFrom(new FileInputStream(splitwellBundle))
      val downloadedBundle = splitwellValidatorBackend.getAppBundle("splitwell")
      uploadedBundle shouldBe downloadedBundle
    }
    "support app installation" in { implicit env =>
      val splitwell = splitwellValidatorBackend.listRegisteredApps().loneElement
      aliceValidatorBackend.installApp(splitwell.manifestUrl)
      aliceValidatorBackend.listInstalledApps() shouldBe Seq(
        InstalledApp(
          "splitwell",
          "http://localhost:3002",
        )
      )
    }
  }
}
