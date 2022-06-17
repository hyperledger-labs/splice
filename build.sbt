import Dependencies._

/*
 * sbt-settings that will be shared between all CN apps.
 */

BuildCommon.sbtSettings

/*
 * Root project
 */
lazy val root = (project in file("."))
  .settings(name := "Hello", libraryDependencies ++= Seq(scalatest % Test))
