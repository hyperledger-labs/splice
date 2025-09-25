package org.lfdecentralizedtrust.splice.integration.tests.runbook

import org.lfdecentralizedtrust.splice.integration.EnvironmentDefinition
import org.lfdecentralizedtrust.splice.integration.tests.FrontendIntegrationTestWithSharedEnvironment

import scala.concurrent.duration.DurationInt

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

import io.circe.parser.parse

/** Preflight test that makes sure that *our* SVs (1-3 + DA-1) have initialized fine.
  */
class DsoPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("sv", "docs")
    with PreflightIntegrationTestUtil
    with SvUiPreflightIntegrationTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false
  override protected def runTokenStandardCliSanityCheck: Boolean = false

  override def environmentDefinition: SpliceEnvironmentDefinition =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName()
    )

  "SVs 1-3 + DA-1 are online and reachable via their public HTTP API" in { implicit env =>
    env.svs.remote.foreach(sv =>
      clue(s"Checking SV at ${sv.httpClientConfig.url}") {
        eventuallySucceeds(timeUntilSuccess = 2.minutes) {
          sv.getDsoInfo()
        }
      }
    )
  }

  "INFO endpoints of SVs 1-3 + DA-1 are reachable and return JSON Object" in { _ =>
    for (ingressName <- coreSvIngressNames.values) {
      val infoUrl = s"https://info.${ingressName}.${sys.env("NETWORK_APPS_ADDRESS")}"

      val urls = Array(
        s"${infoUrl}",
        s"${infoUrl}/runtime/dso.json",
      )

      urls.foreach { url =>
        val client = HttpClient.newHttpClient()
        val uri = URI.create(url)
        val request = HttpRequest.newBuilder().uri(uri).build()

        clue(s"Checking INFO at ${url}") {
          eventuallySucceeds(timeUntilSuccess = 5.minutes) {
            val response = client.send(request, HttpResponse.BodyHandlers.ofString())
            assert(
              response.statusCode() == 200,
              s"Unexpected HTTP status code: ${response.statusCode()}",
            )
            val body = response.body()
            val json = parse(body).getOrElse(fail("Invalid JSON"))
            assert(json.isObject, s"Response body must be a JSON object, but got: $body")
          }
        }
      }
    }
  }

  "The Web UIs of SVs 1-3 + DA-1 are reachable and working as expected" in { env =>
    // we put many checks in one test case to reduce testing time (logging in is slow)
    for ((svName, ingressName) <- coreSvIngressNames) {
      val svUiUrl = s"https://sv.${ingressName}.${sys.env("NETWORK_APPS_ADDRESS")}/";
      // hardcoded to save on four environment variables; we don't expect this to change often
      val svUsername = s"admin@${svName}-dev.com";
      // our current practice is to use the same password for all SVs
      val svPassword = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD")
      val sv = env.svs.remote.find(sv => sv.name == svName).value
      val svInfo = eventuallySucceeds() { sv.getDsoInfo() }

      val votedSvParties =
        env.svs.remote.filter(_ != sv).map(sv_ => eventuallySucceeds() { sv_.getDsoInfo().svParty })

      withFrontEnd("sv") { implicit webDriver =>
        testSvUi(
          svUiUrl,
          svUsername,
          svPassword,
          Some(svInfo),
          votedSvParties,
        )
      }
    }
  }

  "The docs are reachable and working" in { _ =>
    val docsUrl = s"https://${sys.env("NETWORK_APPS_ADDRESS")}/";
    withFrontEnd("docs") { implicit webDriver =>
      silentActAndCheck(
        "load docs",
        go to docsUrl,
      )(
        "The docs are live",
        { _ =>
          find(id("global-synchronizer-for-the-canton-network")) should not be empty
        },
      )
    }
  }
}
