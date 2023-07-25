package com.daml.network.integration.tests

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Uri}
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
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
import java.security.interfaces.RSAPublicKey

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
    "support oauth2 authorization code grant" in { implicit env =>
      val state = "dummyState"
      val userId = "myuser"
      val redirectUri = "https://example.com"
      val clientId = "dummclientid"
      val redirected = Uri(aliceValidatorBackend.authorize(redirectUri, state, userId))
      val code = redirected.query().get("code").value
      val token = aliceValidatorBackend.oauth2Token("code", code, redirectUri, clientId).accessToken

      // Test that we can validate the token against the JWKS provided through the app manager.
      val jwt = JWT.decode(token)
      // This reads the openid configuration first to get the jwks url and then reads the
      // jwks from that url.
      val jwkProvider = new UrlJwkProvider(
        aliceValidatorBackend.config.appManager.value.appManagerApiUrl
          .withPath(Uri.Path("/app-manager/oauth2"))
          .toString
      )
      val jwk = jwkProvider.get(jwt.getKeyId())
      val publicKey = jwk.getPublicKey.asInstanceOf[RSAPublicKey]
      val algorithm = Algorithm.RSA256(publicKey, null)
      val verifier = JWT.require(algorithm).build()
      val decodedJwt = verifier.verify(token)
      decodedJwt.getSubject() shouldBe userId
    }
  }
}
