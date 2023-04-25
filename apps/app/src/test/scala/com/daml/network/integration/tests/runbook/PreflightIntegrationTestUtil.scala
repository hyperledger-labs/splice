package com.daml.network.integration.tests.runbook

import com.daml.network.config.CNHttpClientConfig.*
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import monocle.macros.syntax.lens.*
import org.scalatest.OptionValues.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}

trait PreflightIntegrationTestUtil {

  // We cache this because we only need it for one test case in each suite
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var validatorOnboardingSecret: Option[String] = None

  protected def insertValidatorOnboardingSecret(conf: CNNodeConfig): CNNodeConfig = {
    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc => {
      val oc = vc.onboarding.value

      // obtain an onboarding secret
      val secret = validatorOnboardingSecret match {
        case Some(s) => s
        case None => {
          val s = prepareValidatorOnboarding(oc.remoteSv.adminApi.url)
          validatorOnboardingSecret = Some(s)
          s
        }
      }
      // insert it
      vc.focus(_.onboarding).replace(Some(oc.copy(secret = secret)))
    })(conf)
  }

  // We invoke the API via a basic HTTP request, just like we expect runbook users to do for now.
  private def prepareValidatorOnboarding(url: String): String = {
    val client = HttpClient
      .newBuilder()
      .connectTimeout(java.time.Duration.ofSeconds(20))
      .build()

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(s"$url/devnet/onboard/validator/prepare"))
      .header("content-type", "text/plain")
      .POST(HttpRequest.BodyPublishers.ofString("{\"expires_in\":3600}"))
      .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    response.body
  }

}
