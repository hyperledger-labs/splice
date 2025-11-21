// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.config

import com.google.auth.oauth2.{GoogleCredentials, UserCredentials, ServiceAccountCredentials}
import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.Paths

sealed abstract class GcpCredentialsConfig extends Product with Serializable {
  def credentials: GoogleCredentials
  protected def stringToInputStream(s: String): InputStream =
    new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8))

}

object GcpCredentialsConfig {

  def hideConfidential(config: GcpCredentialsConfig): GcpCredentialsConfig = {
    val hidden = "****"
    config match {
      case User(_) => User(hidden)
      case ServiceAccount(_) => ServiceAccount(hidden)
    }
  }

  final case class User(
      jsonCredentials: String
  ) extends GcpCredentialsConfig {
    override def credentials: com.google.auth.oauth2.UserCredentials =
      UserCredentials.fromStream(stringToInputStream(jsonCredentials))
  }

  final case class ServiceAccount(
      jsonCredentials: String
  ) extends GcpCredentialsConfig {
    override def credentials: com.google.auth.oauth2.ServiceAccountCredentials =
      ServiceAccountCredentials.fromStream(stringToInputStream(jsonCredentials))
  }
}

final case class GcpBucketConfig(
    credentials: GcpCredentialsConfig,
    projectId: String,
    bucketName: String,
) {
  def description: String = s"GCP bucket $bucketName in project $projectId"
}

sealed trait BucketName {
  def value: String
}

case object IdentityDumpTest extends BucketName {
  val value: String = "da-splice-identity-dumps"
}

case object TopologySnapshotTest extends BucketName {
  val value: String = "da-splice-topology-snapshots"
}

case object IdentityDump extends BucketName {
  val value: String = "da-cn-data-dumps"
}

case object TopologySnapshot extends BucketName {
  val value: String = "da-cn-topology-snapshots"
}

object GcpBucketConfig {
  def inferForTesting(testBucketName: BucketName): GcpBucketConfig =
    infer(
      "GCP_DATA_EXPORT_INTEGRATION_TEST_SERVICE_ACCOUNT_CREDENTIALS",
      "da-cn-splice",
      testBucketName.value,
    )

  def inferForCluster(bucketName: BucketName): GcpBucketConfig =
    infer("GCP_DATA_DUMP_BUCKET_SERVICE_ACCOUNT_CREDENTIALS", "da-cn-devnet", bucketName.value)

  def inferForBundles: GcpBucketConfig =
    infer(
      "GCP_DATA_EXPORT_INTEGRATION_TEST_SERVICE_ACCOUNT_CREDENTIALS",
      "da-cn-shared",
      "cn-release-bundles",
    )

  private def infer(envVar: String, projectId: String, bucketName: String): GcpBucketConfig = {
    val credentialsConfig =
      sys.env.get(envVar) match {
        case Some(credentials) => GcpCredentialsConfig.ServiceAccount(credentials)
        case None =>
          val homeDir = Paths.get(System.getProperty("user.home"))
          // Provisioned by direnv
          val userCredentialsPath =
            homeDir.resolve(".config/gcloud/application_default_credentials.json")
          GcpCredentialsConfig.User(scala.io.Source.fromFile(userCredentialsPath.toFile).mkString)
      }
    GcpBucketConfig(credentialsConfig, projectId, bucketName)
  }
}
