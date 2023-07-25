package com.daml.network.integration.tests

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Uri}
import com.daml.network.config.CNNodeConfigTransforms
import com.daml.network.environment.CNNodeEnvironmentImpl
import com.daml.network.http.v0.definitions.RegisteredApp
import com.daml.network.integration.CNNodeEnvironmentDefinition
import com.daml.network.integration.tests.CNNodeTests.{
  CNNodeIntegrationTest,
  CNNodeTestConsoleEnvironment,
}
import com.daml.network.util.WalletTestUtil
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.google.protobuf.ByteString

import java.io.{File, FileInputStream}

class AppManagerIntegrationTest extends CNNodeIntegrationTest with WalletTestUtil {

  override def environmentDefinition
      : BaseEnvironmentDefinition[CNNodeEnvironmentImpl, CNNodeTestConsoleEnvironment] =
    CNNodeEnvironmentDefinition
      .simpleTopology(this.getClass.getSimpleName)
      .addConfigTransforms(
        CNNodeConfigTransforms.onlySv1
      )

  private val splitwellBundle = new File(
    "apps/splitwell/src/test/resources/splitwell-bundle.tar.gz"
  )

  "app manager" should {
    "allow app registration" in { implicit env =>
      val entity = HttpEntity.fromFile(
        ContentTypes.`application/octet-stream`,
        splitwellBundle,
      )
      splitwellValidatorBackend.registerApp(
        entity
      )
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
  }
}
