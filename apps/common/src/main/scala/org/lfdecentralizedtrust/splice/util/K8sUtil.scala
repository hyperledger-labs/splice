// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import io.fabric8.kubernetes.api.model.{Secret, SecretBuilder}
import org.lfdecentralizedtrust.splice.auth.AuthToken
import com.digitalasset.canton.data.CantonTimestamp
import com.typesafe.scalalogging.Logger
import io.circe.parser.decode as circeDecode

import scala.annotation.nowarn
import scala.util.control.NonFatal

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

    private case class Auth0PreflightTokenData(accessToken: String, expiresAtMillis: Long) {
      def toAuthToken: AuthToken =
        AuthToken(
          accessToken,
          expiresAt = CantonTimestamp.assertFromLong(micros = expiresAtMillis * 1000),
          user = AuthToken.guessLedgerApiUser(accessToken),
        )
    }

    @nowarn("cat=lint-byname-implicit") // https://github.com/scala/bug/issues/12072
    private object TokenDataJsonProtocol {
      import io.circe.{Decoder, Encoder}
      import io.circe.generic.semiauto.*

      implicit val tokenDecoder: Decoder[Auth0PreflightTokenData] = deriveDecoder
      implicit val tokenEncoder: Encoder[Auth0PreflightTokenData] = deriveEncoder
    }
    import TokenDataJsonProtocol.*

    def getPreflightToken(clientId: String)(implicit logger: Logger): Option[AuthToken] = {
      readSecretField(preflightTokenSecretName, clientId) match {
        case Some(secretData) =>
          // TODO (#8039): remove try catch, logging and fallback to None (let it crash, which shouldn't happen)
          try {
            val token =
              circeDecode[Auth0PreflightTokenData](secretData).fold(throw _, identity).toAuthToken
            if (token.expiresAt.isBefore(CantonTimestamp.now())) {
              None
            } else {
              Some(token)
            }
          } catch {
            case NonFatal(ex) =>
              logger.info(
                "Failed to parse k8s secret containing Auth0Token." +
                  "This is probably because an older version of the JSON was stored.",
                ex,
              )
              None
          }
        case None => None
      }
    }

    def savePreflightToken(clientId: String, token: AuthToken) = {
      import io.circe.syntax.*
      val tokenJson = Auth0PreflightTokenData(
        token.accessToken,
        expiresAtMillis = token.expiresAt.toEpochMilli,
      ).asJson.noSpaces

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
