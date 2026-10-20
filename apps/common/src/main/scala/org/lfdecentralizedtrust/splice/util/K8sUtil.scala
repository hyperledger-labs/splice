// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*
import io.fabric8.kubernetes.api.model.{Secret, SecretBuilder}


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
  }
}
