package com.daml.network.util

import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import io.fabric8.kubernetes.api.model.SecretBuilder
import io.fabric8.kubernetes.api.model.Secret
import com.daml.network.auth.AuthToken
import com.digitalasset.canton.data.CantonTimestamp

object K8sUtil {
  private val k8s: KubernetesClient = new KubernetesClientBuilder().build()

  private def getSecretO(secretName: String, namespace: String): Option[Secret] =
    Option(
      k8s
        .secrets()
        .inNamespace(namespace)
        .withName(secretName)
        .get()
    )

  def getSecretByName(
      secretName: String,
      namespace: String = "default",
  ): Option[scala.collection.mutable.Map[String, String]] = {
    getSecretO(secretName, namespace).map(_.getData().asScala)
  }

  def readSecretField(secretName: String, field: String): Option[String] = for {
    data <- getSecretByName(secretName)
    encodedSecretData <- data.get(field)
  } yield new String(
    java.util.Base64.getDecoder().decode(encodedSecretData),
    StandardCharsets.UTF_8,
  )

  def saveSecret(
      secretName: String,
      secret: Secret,
      namespace: String = "default",
  ): Secret = {
    getSecretO(secretName, namespace) match {
      case Some(_) => {
        k8s
          .secrets()
          .inNamespace(namespace)
          .withName(secretName)
          .edit((s: Secret) => new SecretBuilder(s).addToData(secret.getData()).build())
      }
      case None => {
        k8s
          .secrets()
          .inNamespace(namespace)
          .resource(secret)
          .create()
      }
    }
  }

  object PreflightTokenAccessor {
    private val preflightTokenSecretName = "auth0-preflight-token-cache"

    import spray.json.*
    private case class Auth0PreflightTokenData(accessToken: String, expiresIn: Long) {
      def toAuthToken: AuthToken =
        AuthToken(accessToken, CantonTimestamp.now().plusMillis(expiresIn))
    }

    private object TokenDataJsonProtocol extends DefaultJsonProtocol {
      implicit val tokenFormat = jsonFormat2(Auth0PreflightTokenData)
    }
    import TokenDataJsonProtocol.*

    def getPreflightToken(clientId: String): Option[AuthToken] = {
      readSecretField(preflightTokenSecretName, clientId) match {
        case Some(secretData) => {
          val token = secretData.parseJson.convertTo[Auth0PreflightTokenData].toAuthToken
          if (token.expiresAt.isAfter(CantonTimestamp.now())) {
            None
          } else {
            Some(token)
          }
        }
        case None => None
      }
    }

    def savePreflightToken(clientId: String, token: AuthToken) = {
      val tokenJson = Auth0PreflightTokenData(
        token.accessToken,
        CantonTimestamp.now().toEpochMilli - token.expiresAt.toEpochMilli,
      ).toJson.compactPrint

      val secret = new SecretBuilder()
        .withNewMetadata()
        .withName(preflightTokenSecretName)
        .endMetadata()
        .addToData(clientId, java.util.Base64.getEncoder().encodeToString(tokenJson.getBytes()))
        .build()

      saveSecret(
        preflightTokenSecretName,
        secret,
      )
    }
  }
}
