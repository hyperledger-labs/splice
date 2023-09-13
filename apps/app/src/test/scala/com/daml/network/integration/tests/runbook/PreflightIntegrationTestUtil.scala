package com.daml.network.integration.tests.runbook

import akka.http.scaladsl.model.Uri
import com.daml.network.config.{CNNodeConfig, CNNodeConfigTransforms}
import com.daml.network.util.{Auth0Util, K8sUtil}
import monocle.macros.syntax.lens.*
import org.scalatest.OptionValues.*

import java.io.IOException
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.{HttpURLConnection, URI}

trait PreflightIntegrationTestUtil {

  // We cache this because we only need it for one test case in each suite
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  var validatorOnboardingSecret: Option[String] = None

  protected def getAuth0ClientCredential(
      clientId: String,
      audience: String,
      auth0: Auth0Util,
  ): String = {
    // lookup token from a cached k8s secret, or request a new one from auth0 if not found or expired
    val cachedToken =
      K8sUtil.PreflightTokenAccessor.getPreflightToken(clientId)

    cachedToken.getOrElse {
      val token = auth0.getToken(clientId, audience)
      K8sUtil.PreflightTokenAccessor.savePreflightToken(clientId, token)
      token
    }.accessToken
  }

  protected def insertValidatorOnboardingSecret(conf: CNNodeConfig): CNNodeConfig = {
    CNNodeConfigTransforms.updateAllValidatorConfigs_(vc => {
      val oc = vc.onboarding.value

      // obtain an onboarding secret
      val secret = validatorOnboardingSecret match {
        case Some(s) => s
        case None => {
          val s = prepareValidatorOnboarding(oc.svClient.adminApi.url)
          validatorOnboardingSecret = Some(s)
          s
        }
      }
      // insert it
      vc.focus(_.onboarding).replace(Some(oc.copy(secret = secret)))
    })(conf)
  }

  // We invoke the API via a basic HTTP request, just like we expect runbook users to do for now.
  private def prepareValidatorOnboarding(url: Uri): String = {
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
    if (response.statusCode() == HttpURLConnection.HTTP_OK)
      response.body
    else
      throw new IOException(response.body)
  }

}
