import sbt._

object Dependencies {
  lazy val scala_version = "2.13.8"

  lazy val scalatest_version = "3.2.9"

  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version
}
