package com.daml.network.tools

object Tools {
  def main(args: Array[String]) = {
    Auth0TestUserCleaner.run()
  }

  def readMandatoryEnvVar(name: String): String = {
    sys.env.get(name) match {
      case None => sys.error(s"Environment variable $name must be set")
      case Some(s) if s.isEmpty => sys.error(s"Environment variable $name must be non-empty")
      case Some(s) => s
    }
  }
}
