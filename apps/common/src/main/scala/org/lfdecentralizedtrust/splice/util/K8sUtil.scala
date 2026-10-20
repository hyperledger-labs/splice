// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import io.fabric8.kubernetes.client.{KubernetesClient, KubernetesClientBuilder}

import scala.jdk.CollectionConverters.*
import io.fabric8.kubernetes.api.model.Secret

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

}
