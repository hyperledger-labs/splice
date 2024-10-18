package org.lfdecentralizedtrust.splice.integration.tests

import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.client.RequestBuilding.{Options, Post}
import org.apache.pekko.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpMethods,
  StatusCodes,
  Uri,
}
import org.apache.pekko.http.scaladsl.model.headers
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.lfdecentralizedtrust.splice.auth.AuthUtil
import org.lfdecentralizedtrust.splice.config.ConfigTransforms
import org.lfdecentralizedtrust.splice.environment.EnvironmentImpl
import org.lfdecentralizedtrust.splice.http.v0.definitions.{
  AppConfiguration,
  Domain,
  InstalledApp,
  RegisteredApp,
  ReleaseConfiguration,
  Timespan,
  UnapprovedReleaseConfiguration,
}
import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.SpliceTests.{
  IntegrationTestWithSharedEnvironment,
  SpliceTestConsoleEnvironment,
}
import org.lfdecentralizedtrust.splice.util.WalletTestUtil
import com.digitalasset.canton.config.NonNegativeFiniteDuration
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.google.protobuf.ByteString

import java.io.{File, FileInputStream}
import java.security.interfaces.RSAPublicKey
import scala.util.Success

class AppManagerIntegrationTest extends IntegrationTestWithSharedEnvironment with WalletTestUtil {

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

  private def splitwellBundleUpgradeEntity = HttpEntity.fromFile(
    ContentTypes.`application/octet-stream`,
    splitwellBundleUpgrade,
  )
  val releaseConfiguration1 =
    ReleaseConfiguration(
      domains = Vector(
        Domain(
          "splitwell",
          "http://localhost:5708",
        )
      ),
      releaseVersion = "1.0.0",
      requiredFor = Timespan(),
    )

  private val redirectUri = "http://localhost:3420/redirect_me_here"
  private val configuration1_0 = AppConfiguration(
    0L,
    "splitwell",
    "http://localhost:3420",
    Vector(redirectUri),
    Vector(
      releaseConfiguration1
    ),
  )

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition
      .simpleTopology1Sv(this.getClass.getSimpleName)
      .addConfigTransforms(
        (_, config) => ConfigTransforms.disableSplitwellUserDomainConnections(config),
        (_, config) =>
          ConfigTransforms.updateAllAppManagerConfigs_(
            _.copy(
              installedAppsPollingInterval = NonNegativeFiniteDuration.ofSeconds(1)
            )
          )(config),
      )
      .withoutInitialManagerApps
      .withAdditionalSetup { implicit env =>
        // Shared integration test so we upload here rather than within the tests.
        splitwellValidatorBackend.registerApp(
          splitwellBackend.config.providerUser,
          configuration1_0,
          splitwellBundleEntity,
        )
      }

  "app manager" should {

    "find an app by name" in { implicit env =>
      splitwellValidatorBackend.getLatestAppConfigurationByName(configuration1_0.name) should be(
        configuration1_0
      )
    }

    "support app registration" in { implicit env =>
      val provider = splitwellBackend.getProviderPartyId()
      splitwellValidatorBackend.listRegisteredApps() shouldBe Seq(
        RegisteredApp(
          provider.toProtoPrimitive,
          splitwellValidatorBackend.config.appManager.value.appManagerApiUrl
            .withPath(
              Uri.Path(
                s"/api/validator/v0/app-manager/apps/registered/${provider.toProtoPrimitive}"
              )
            )
            .toString,
          configuration1_0,
        )
      )
      val configuration = splitwellValidatorBackend.getLatestAppConfiguration(provider)
      configuration.name shouldBe "splitwell"
      configuration.releaseConfigurations.loneElement.domains.loneElement shouldBe Domain(
        "splitwell",
        "http://localhost:5708",
      )
      val darHash = splitwellValidatorBackend
        .getAppRelease(provider, configuration.releaseConfigurations.loneElement.releaseVersion)
        .darHashes
        .loneElement

      val uploadedDar =
        ByteString.readFrom(new FileInputStream("daml/splitwell/.daml/dist/splitwell-current.dar"))
      val downloadedDar = splitwellValidatorBackend.getDarFile(darHash)
      uploadedDar shouldBe downloadedDar
    }

    "fail to register an app with the same name" in { implicit env =>
      // note that this was called already in the `environmentDefinition`
      assertThrowsAndLogsCommandFailures(
        splitwellValidatorBackend.registerApp(
          splitwellBackend.config.providerUser,
          configuration1_0,
          splitwellBundleEntity,
        ),
        _.errorMessage should include(s"App with name ${configuration1_0.name} already exists."),
      )
    }

    "fail to authorize if the app does not exist" in { implicit env =>
      val provider = splitwellBackend.getProviderPartyId()
      val state = "dummyState"
      val badRedirectUri = "https://nope.example.com"
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      // nope: aliceValidatorBackend.installApp(splitwell.appUrl)
      assertThrowsAndLogsCommandFailures(
        aliceAppManagerClient.authorizeApp(provider),
        _.errorMessage should include(
          s"App $provider does not exist."
        ),
      )
      assertThrowsAndLogsCommandFailures(
        aliceAppManagerClient.checkAppAuthorized(provider, badRedirectUri, state),
        _.errorMessage should include(
          s"App $provider does not exist."
        ),
      )
    }

    "support app installation" in { implicit env =>
      val provider = splitwellBackend.getProviderPartyId()
      val splitwell = splitwellValidatorBackend.listRegisteredApps().loneElement
      aliceValidatorBackend.installApp(splitwell.appUrl)
      aliceValidatorBackend.listInstalledApps() shouldBe Seq(
        InstalledApp(
          splitwell.provider,
          configuration1_0,
          Vector.empty,
          Vector(UnapprovedReleaseConfiguration(0, releaseConfiguration1)),
        )
      )
      aliceValidatorBackend.approveAppReleaseConfiguration(provider, 0, 0)
      aliceValidatorBackend.listInstalledApps() shouldBe Seq(
        InstalledApp(
          splitwell.provider,
          configuration1_0,
          Vector(releaseConfiguration1),
          Vector.empty,
        )
      )
    }
    "support publishing new releases and updating configurations" in { implicit env =>
      val provider = splitwellBackend.getProviderPartyId()
      splitwellValidatorBackend.getLatestAppConfiguration(provider).version shouldBe 0
      val splitwell = splitwellValidatorBackend.listRegisteredApps().loneElement
      aliceValidatorBackend.installApp(splitwell.appUrl)
      aliceValidatorBackend.listInstalledApps().loneElement.latestConfiguration.version shouldBe 0
      // publish configuration for the same release version
      splitwellValidatorBackend.updateAppConfiguration(provider, configuration1_0.copy(version = 1))
      splitwellValidatorBackend.getLatestAppConfiguration(provider).version shouldBe 1
      assertThrowsAndLogsCommandFailures(
        splitwellValidatorBackend.updateAppConfiguration(
          provider,
          configuration1_0.copy(
            version = 3
          ),
        ),
        _.errorMessage should include(
          "Tried to update configuration to version 3 but previous config was version 1"
        ),
      )
      // cannot publish for version != currentVersion + 1
      // publish configuration with new release version before uploading release,
      val newReleaseConfig = ReleaseConfiguration(
        domains = Vector.empty,
        releaseVersion = "2.0.0",
        requiredFor = Timespan(),
      )
      assertThrowsAndLogsCommandFailures(
        splitwellValidatorBackend.updateAppConfiguration(
          provider,
          configuration1_0.copy(
            version = 2,
            releaseConfigurations = newReleaseConfig
              +: configuration1_0.releaseConfigurations,
          ),
        ),
        _.errorMessage should include("release with that version has not been uploaded"),
      )
      assertThrowsAndLogsCommandFailures(
        splitwellValidatorBackend.getAppRelease(provider, "2.0.0").version,
        _.errorMessage should include("No release 2.0.0 found"),
      )
      splitwellValidatorBackend.publishAppRelease(provider, splitwellBundleUpgradeEntity)
      splitwellValidatorBackend.getAppRelease(provider, "2.0.0").version shouldBe "2.0.0"
      actAndCheck(
        "provider publishes config version 2",
        splitwellValidatorBackend.updateAppConfiguration(
          provider,
          configuration1_0.copy(
            version = 2,
            releaseConfigurations = newReleaseConfig
              +: configuration1_0.releaseConfigurations,
          ),
        ),
      )(
        "provider and user both observe update",
        _ => {
          splitwellValidatorBackend.getLatestAppConfiguration(provider).version shouldBe 2
          aliceValidatorBackend
            .listInstalledApps()
            .loneElement
            .latestConfiguration
            .version shouldBe 2
        },
      )
      // Check that the new release config can be approved.
      aliceValidatorBackend
        .listInstalledApps()
        .loneElement
        .unapprovedReleaseConfigurations
        .loneElement shouldBe
        UnapprovedReleaseConfiguration(
          0,
          newReleaseConfig,
        )
      aliceValidatorBackend.approveAppReleaseConfiguration(provider, 2, 0)
      aliceValidatorBackend
        .listInstalledApps()
        .loneElement
        .unapprovedReleaseConfigurations shouldBe empty
    }
    "support oauth2 authorization code grant" in { implicit env =>
      val provider = splitwellBackend.getProviderPartyId()
      val state = "dummyState"
      val userId = aliceWalletClient.config.ledgerApiUser
      val clientId = "dummclientid"
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val splitwell = splitwellValidatorBackend.listRegisteredApps().loneElement
      aliceValidatorBackend.installApp(splitwell.appUrl)
      assertThrowsAndLogsCommandFailures(
        aliceAppManagerClient.checkAppAuthorized(provider, redirectUri, state),
        _.errorMessage should include("not authorized"),
      )
      aliceAppManagerClient.authorizeApp(provider)
      val redirected =
        Uri(aliceAppManagerClient.checkAppAuthorized(provider, redirectUri, state))
      val code = redirected.query().get("code").value
      val token = aliceValidatorBackend.oauth2Token("code", code, redirectUri, clientId).accessToken

      // Test that we can validate the token against the JWKS provided through the app manager.
      val jwt = JWT.decode(token)
      // This reads the openid configuration first to get the jwks url and then reads the
      // jwks from that url.
      val jwkProvider = new UrlJwkProvider(
        aliceValidatorBackend.config.appManager.value.appManagerApiUrl
          .withPath(Uri.Path("/api/validator/v0/app-manager/oauth2"))
          .toString
      )
      val jwk = jwkProvider.get(jwt.getKeyId())
      val publicKey = jwk.getPublicKey.asInstanceOf[RSAPublicKey]
      val algorithm = Algorithm.RSA256(publicKey, null)
      val verifier = JWT.require(algorithm).build()
      val decodedJwt = verifier.verify(token)
      decodedJwt.getSubject() shouldBe s"app.$provider.$userId"
    }

    "fail for an invalid redirect_uri" in { implicit env =>
      val provider = splitwellBackend.getProviderPartyId()
      val state = "dummyState"
      val badRedirectUri = "https://nope.example.com"
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      val splitwell = splitwellValidatorBackend.listRegisteredApps().loneElement
      aliceValidatorBackend.installApp(splitwell.appUrl)
      aliceAppManagerClient.authorizeApp(provider)
      assertThrowsAndLogsCommandFailures(
        aliceAppManagerClient.checkAppAuthorized(provider, badRedirectUri, state),
        _.errorMessage should include(
          "The provided redirectUri is not in the list of allowed redirect URIs."
        ),
      )
    }

    "json api proxy sets CORS headers" in { implicit env =>
      implicit val sys = env.actorSystem
      registerHttpConnectionPoolsCleanup(env)

      val preflight =
        Options(
          s"${aliceValidatorBackend.config.appManager.value.appManagerApiUrl}/api/validator/jsonApiProxy/v1/user"
        )
          .withHeaders(
            headers.Origin("http://example.com"),
            headers.`Access-Control-Request-Method`(HttpMethods.POST),
            headers.`Access-Control-Request-Headers`("origin", "authorization"),
          )

      val response = Http()
        .singleRequest(preflight)
        .futureValue
      inside(response) {
        case _ if response.status == StatusCodes.OK =>
          response.headers should contain(
            headers.`Access-Control-Allow-Methods`(
              HttpMethods.DELETE,
              HttpMethods.GET,
              HttpMethods.POST,
              HttpMethods.HEAD,
              HttpMethods.OPTIONS,
            )
          )
      }
    }
    "json api proxy is setup correctly" in { implicit env =>
      implicit val sys = env.actorSystem
      // We don't expose the full proxy through the ValidatorReference since it just adds a bunch of boilerplate
      // with little benefit so instead we just make the one request for testing manually here.
      registerHttpConnectionPoolsCleanup(env)

      val getUser =
        Post(s"${aliceValidatorBackend.config.appManager.value.jsonApiUrl}/v1/user")
          .withEntity(
            HttpEntity(
              ContentTypes.`application/json`,
              s"""{"userId": "${aliceValidatorBackend.config.ledgerApiUser}"}""",
            )
          )
          .withHeaders(
            headers.Authorization(headers.OAuth2BearerToken(aliceValidatorBackend.token.value))
          )

      val response = Http()
        .singleRequest(
          getUser
        )
        .futureValue
      inside(response) {
        case _ if response.status == StatusCodes.OK =>
          inside(Unmarshal(response.entity).to[String].value.value) { case Success(response) =>
            response shouldBe s"""{"result":{"primaryParty":"${aliceValidatorBackend
                .getValidatorPartyId()
                .toProtoPrimitive}","userId":"${aliceValidatorBackend.config.ledgerApiUser}"},"status":200}"""
          }
      }
    }
    "app manager admin API auth is setup correctly" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      implicit val sys = env.actorSystem
      registerHttpConnectionPoolsCleanup(env)

      val splitwell = splitwellValidatorBackend.listRegisteredApps().loneElement
      val request = Post(
        s"${aliceValidatorBackend.httpClientConfig.url}/api/validator/v0/app-manager/apps/install"
      )
        .withEntity(
          HttpEntity(
            ContentTypes.`application/json`,
            s"""{"app_url": "${splitwell.appUrl}"}""",
          )
        )
      def tokenHeader(token: String) = Seq(headers.Authorization(headers.OAuth2BearerToken(token)))
      val adminJwt = JWT
        .create()
        .withAudience(aliceValidatorBackend.config.auth.audience)
        .withSubject(aliceValidatorBackend.config.ledgerApiUser)
        .sign(AuthUtil.testSignatureAlgorithm)
      Http()
        .singleRequest(request.withHeaders(tokenHeader(adminJwt)))
        .futureValue
        .status shouldBe StatusCodes.Created
      val nonAdminJwt = JWT
        .create()
        .withAudience(aliceValidatorBackend.config.auth.audience)
        .withSubject(aliceWalletClient.config.ledgerApiUser)
        .sign(AuthUtil.testSignatureAlgorithm)
      loggerFactory.assertLogs(
        Http()
          .singleRequest(request.withHeaders(tokenHeader(nonAdminJwt)))
          .futureValue
          .status shouldBe StatusCodes.Forbidden,
        _.warningMessage should include("Authorization Failed"),
      )
      Http()
        .singleRequest(request)
        .futureValue
        .status shouldBe StatusCodes.Unauthorized
    }
    "app manager user API auth is setup correctly" in { implicit env =>
      onboardWalletUser(aliceWalletClient, aliceValidatorBackend)
      implicit val sys = env.actorSystem
      registerHttpConnectionPoolsCleanup(env)

      val splitwell = splitwellValidatorBackend.listRegisteredApps().loneElement
      aliceValidatorBackend.installApp(splitwell.appUrl)
      val request = Post(
        s"${aliceValidatorBackend.httpClientConfig.url}/api/validator/v0/app-manager/apps/installed/${splitwell.provider}/authorize"
      )
      def tokenHeader(token: String) = Seq(headers.Authorization(headers.OAuth2BearerToken(token)))
      val nonAdminJwt = JWT
        .create()
        .withAudience(aliceValidatorBackend.config.auth.audience)
        .withSubject(aliceWalletClient.config.ledgerApiUser)
        .sign(AuthUtil.testSignatureAlgorithm)
      Http()
        .singleRequest(request.withHeaders(tokenHeader(nonAdminJwt)))
        .futureValue
        .status shouldBe StatusCodes.OK
      val nonExistentUserJwt = JWT
        .create()
        .withAudience(aliceValidatorBackend.config.auth.audience)
        .withSubject("foobar")
        .sign(AuthUtil.testSignatureAlgorithm)
      // TODO(#7267) This should fail with Forbidden once the auth extractor
      // checks that the user is allocated.
      Http()
        .singleRequest(request.withHeaders(tokenHeader(nonExistentUserJwt)))
        .futureValue
        .status shouldBe StatusCodes.NotFound
      Http()
        .singleRequest(request)
        .futureValue
        .status shouldBe StatusCodes.Unauthorized
    }
  }
}
