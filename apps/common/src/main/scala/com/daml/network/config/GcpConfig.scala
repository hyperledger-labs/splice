package com.daml.network.config

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
    override def credentials =
      UserCredentials.fromStream(stringToInputStream(jsonCredentials))
  }

  final case class ServiceAccount(
      jsonCredentials: String
  ) extends GcpCredentialsConfig {
    override def credentials =
      ServiceAccountCredentials.fromStream(stringToInputStream(jsonCredentials))
  }
}

final case class GcpBucketConfig(
    credentials: GcpCredentialsConfig,
    projectId: String,
    bucketName: String,
)

object GcpBucketConfig {
  def inferForTesting: GcpBucketConfig = {
    val credentialsConfig =
      sys.env.get("GCP_BUCKET_SERVICE_ACCOUNT_CREDENTIALS") match {
        case Some(credentials) => GcpCredentialsConfig.ServiceAccount(credentials)
        case None =>
          val homeDir = Paths.get(System.getProperty("user.home"))
          // Provisioned by direnv
          val userCredentialsPath =
            homeDir.resolve(".config/gcloud/application_default_credentials.json")
          GcpCredentialsConfig.User(scala.io.Source.fromFile(userCredentialsPath.toFile).mkString)
      }
    GcpBucketConfig(credentialsConfig, "da-cn-scratchnet", "da-cn-scratch-acs-store-dumps")
  }
}
