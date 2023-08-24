package com.daml.network.tools

object Tools {
  def main(args: Array[String]) = {
    Auth0TestUserCleaner.run(
      "https://canton-network-dev.us.auth0.com",
      Tools.readMandatoryEnvVar("AUTH0_CN_MANAGEMENT_API_CLIENT_ID"),
      Tools.readMandatoryEnvVar("AUTH0_CN_MANAGEMENT_API_CLIENT_SECRET"),
    )

    Auth0TestUserCleaner.run(
      "https://canton-network-test.us.auth0.com",
      Tools.readMandatoryEnvVar("AUTH0_TESTS_MANAGEMENT_API_CLIENT_ID"),
      Tools.readMandatoryEnvVar("AUTH0_TESTS_MANAGEMENT_API_CLIENT_SECRET"),
    )
  }

  def readMandatoryEnvVar(name: String): String = {
    sys.env.get(name) match {
      case None => sys.error(s"Environment variable $name must be set")
      case Some(s) if s.isEmpty => sys.error(s"Environment variable $name must be non-empty")
      case Some(s) => s
    }
  }
}
