package org.lfdecentralizedtrust.splice.unit.http

import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import com.digitalasset.canton.config.{ApiLoggingConfig, NonNegativeDuration}
import com.digitalasset.canton.logging.NamedLogging
import com.digitalasset.canton.{BaseTest, FutureHelpers, HasActorSystem, HasExecutionContext}
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.Http.ServerBinding
import org.apache.pekko.http.scaladsl.model.*
import org.apache.pekko.http.scaladsl.server.Directives.{complete, *}
import org.apache.pekko.http.scaladsl.server.Route
import org.lfdecentralizedtrust.splice.auth.RSAVerifier
import org.lfdecentralizedtrust.splice.http.HttpClient
import org.scalatest.wordspec.AsyncWordSpec

import java.net.URI
import scala.concurrent.duration.*
import scala.concurrent.{ExecutionContext, Future}

class HttpClientProxyTest
    extends AsyncWordSpec
    with BaseTest
    with HasActorSystem
    with HasExecutionContext
    with NamedLogging
    with TinyProxySupport
    with HttpServerSupport
    with SystemPropertiesSupport {

  "HttpClient proxy settings" should {
    "support proxy configuration via http.proxyPort, http.proxyHost" in {
      withProxy() { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val props = SystemProperties()
            .set("http.proxyHost", "localhost")
            .set("http.proxyPort", proxy.port.toString)
          withProperties(props) {
            assertProxiedGetRequest(proxy, serverBinding)
          }
        }
      }
    }
    "not use the proxy if no configuration set" in {
      withProxy() { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val serverPort = serverBinding.localAddress.getPort
          val host = "localhost"
          val response = executeRequest(serverBinding).futureValue
          response.status shouldBe StatusCodes.OK
          proxy.process.hasNoErrors shouldBe true
          proxy.proxiedConnectRequest(host, serverPort) shouldBe false
        }
      }
    }
    "support proxy configuration via https.proxyPort, https.proxyHost" in {
      withProxy() { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val props = SystemProperties()
            .set("https.proxyHost", "localhost")
            .set("https.proxyPort", proxy.port.toString)
          withProperties(props) {
            assertProxiedGetRequest(proxy, serverBinding)
          }
        }
      }
    }
    "support proxy configuration via http.proxyPort, http.proxyHost, http.proxyUser and http.proxyPassword" in {
      val user = "user"
      val password = "pass1"
      withProxy(auth = Some((user, password))) { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val props = SystemProperties()
            .set("http.proxyHost", "localhost")
            .set("http.proxyPort", proxy.port.toString)
            .set("http.proxyUser", user)
            .set("http.proxyPassword", password)
          withProperties(props) {
            assertProxiedGetRequest(proxy, serverBinding)
          }
        }
      }
    }
    "Ensure jwks URL used in JwkProvider supports proxy configuration via http.proxyPort, http.proxyHost" in {
      withProxy() { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val proxyHost = "localhost"
          val serverHost = "localhost"
          val serverPort = serverBinding.localAddress.getPort
          val props =
            SystemProperties()
              .set("http.proxyHost", proxyHost)
              .set("http.proxyPort", proxy.port.toString)
              // localhost and tcp loopback addresses are not proxied by default in gRPC and Java URL connections
              // so we need to override the nonProxyHosts to ensure our proxy is used for all connections
              .set("http.nonProxyHosts", "")
              .set("https.nonProxyHosts", "")

          withProperties(props) {
            val jwksUrl = new URI(
              s"http://localhost:${serverPort}/.well-known/jwks.json"
            ).toURL

            val provider: JwkProvider = new JwkProviderBuilder(jwksUrl)
              .cached(false)
              .build()

            provider.get(Routes.testJwksKeyId).getId shouldBe Routes.testJwksKeyId
            proxy.proxiedConnectRequest(serverHost, serverPort) shouldBe true
          }
        }
      }
    }
    "Ensure jwks URL used in RSAVerifier supports proxy configuration via http.proxyPort, http.proxyHost" in {
      withProxy() { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val proxyHost = "localhost"
          val serverHost = "localhost"
          val serverPort = serverBinding.localAddress.getPort
          val timeout = NonNegativeDuration(5.seconds)
          val props =
            SystemProperties()
              .set("http.proxyHost", proxyHost)
              .set("http.proxyPort", proxy.port.toString)
              // localhost and tcp loopback addresses are not proxied by default in gRPC and Java URL connections
              // so we need to override the nonProxyHosts to ensure our proxy is used for all connections
              .set("http.nonProxyHosts", "")
              .set("https.nonProxyHosts", "")
          withProperties(props) {
            val jwksUrl = new URI(
              s"http://localhost:${serverPort}/.well-known/jwks.json"
            ).toURL
            val verifier = new RSAVerifier(
              "fake-audience",
              jwksUrl,
              RSAVerifier.TimeoutsConfig(
                timeout,
                timeout,
              ),
            )
            verifier.provider.get(Routes.testJwksKeyId).getId shouldBe Routes.testJwksKeyId
            proxy.proxiedConnectRequest(serverHost, serverPort) shouldBe true
          }
        }
      }
    }

    "support proxy configuration via https.proxyPort, https.proxyHost, https.proxyUser and https.proxyPassword" in {
      val user = "user"
      val password = "pass1"
      withProxy(auth = Some((user, password))) { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val props = SystemProperties()
            .set("https.proxyHost", "localhost")
            .set("https.proxyPort", proxy.port.toString)
            .set("https.proxyUser", user)
            .set("https.proxyPassword", password)
          withProperties(props) {
            assertProxiedGetRequest(proxy, serverBinding)
          }
        }
      }
    }
    "fail to connect (Proxy Authentication Required) if http.proxyUser and http.proxyPassword are not set and proxy requires auth" in {
      val user = "user"
      val password = "pass1"
      withProxy(auth = Some((user, password))) { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val props = SystemProperties()
            .set("http.proxyHost", "localhost")
            .set("http.proxyPort", proxy.port.toString)
          withProperties(props) {
            executeRequest(serverBinding).failed.futureValue.getMessage should include(
              "407 Proxy Authentication Required"
            )
          }
        }
      }
    }
    "fail to connect (Unauthorized) using bad proxy credentials" in {
      val user = "user"
      val password = "pass1"
      withProxy(auth = Some((user, password))) { proxy =>
        withHttpServer(Routes.respondWithOK) { serverBinding =>
          val props = SystemProperties()
            .set("http.proxyHost", "localhost")
            .set("http.proxyPort", proxy.port.toString)
            .set("http.proxyUser", user)
            .set("http.proxyPassword", "fail")
          withProperties(props) {
            executeRequest(serverBinding).failed.futureValue.getMessage should include(
              "401 Unauthorized"
            )
          }
        }
      }
    }
  }

  private def executeRequest(serverBinding: ServerBinding) = {
    val serverPort = serverBinding.localAddress.getPort
    val httpClient = HttpClient(
      ApiLoggingConfig(),
      HttpClient.HttpRequestParameters(NonNegativeDuration(30.seconds)),
      logger,
    )
    val uriString = s"http://localhost:$serverPort"

    httpClient
      .executeRequest(
        HttpRequest(uri = Uri(uriString))
      )
  }

  private def assertProxiedGetRequest(proxy: HttpProxy, serverBinding: ServerBinding) = {
    val serverPort = serverBinding.localAddress.getPort
    val host = "localhost"
    val response = executeRequest(serverBinding).futureValue
    response.status shouldBe StatusCodes.OK
    proxy.process.hasNoErrors shouldBe true
    proxy.proxiedConnectRequest(host, serverPort) shouldBe true
  }
}

object Routes {
  val testJwksKeyId = "test-key"
  def respondWithOK: Route = get {
    // fake JWKS response to make sure http.proxy settings are used when RSAVerifier fetches JWKS
    pathPrefix(".well-known" / "jwks.json") {
      complete(
        HttpEntity(
          ContentTypes.`application/json`,
          s"""
            |{
            |  "keys":
            |  [
            |    {
            |      "kty": "RSA",
            |      "use": "sig",
            |      "n": "fake-n",
            |      "e": "additional",
            |      "kid": "$testJwksKeyId",
            |      "x5t": "fake",
            |      "x5c":
            |      [
            |        "fake-x5c"
            |      ],
            |      "alg": "RS256"
            |    }
            |  ]
            |}
          """.stripMargin,
        )
      )
    } ~
      pathEnd {
        complete(StatusCodes.OK)
      }
  }
}

trait SystemPropertiesSupport {
  def withProperties[T](props: SystemProperties)(testCode: => T): T = {
    try {
      testCode
    } finally {
      props.reset()
    }
  }
}

case class SystemProperties(previousProps: Map[String, Option[String]] = Map()) {
  def set(key: String, value: String): SystemProperties = {
    if (previousProps.contains(key)) {
      throw new IllegalArgumentException(
        s"System property $key has already been set through SystemProperties.set"
      )
    }
    if (System.getProperty(key) == value) {
      System.clearProperty(key)
      throw new IllegalArgumentException(s"System property $key is already set to $value")
    }

    val newProps = SystemProperties(
      previousProps + (key -> Option(System.getProperty(key)))
    )
    System.setProperty(key, value)
    newProps
  }
  def reset(): Unit = {
    previousProps.foreach { case (key, value) =>
      value.foreach { v =>
        System.setProperty(key, v)
      }
      if (value.isEmpty) {
        System.clearProperty(key)
      }
    }
  }
}

trait HttpServerSupport extends FutureHelpers {
  def withHttpServer[T](route: Route)(testCode: ServerBinding => T)(implicit
      ac: ActorSystem,
      ec: ExecutionContext,
  ): T = {
    val bindingFut = start(route)
    try {
      testCode(bindingFut.futureValue)
    } finally {
      bindingFut
        .flatMap(_.unbind())
        .recover { case _ => Done }
        .futureValue
    }
  }
  def start(route: Route)(implicit ac: ActorSystem): Future[ServerBinding] = {
    Http()
      .newServerAt("0.0.0.0", 0)
      .bind(route)
  }
}
