import sbt.Keys._
import sbt._
import Dependencies._
import sbt.nio.Keys._

object BuildCommon {

  lazy val sbtSettings: Seq[Def.Setting[_]] = {
    val globalSettings = Seq(
      name := "coin",
      // Automatically reload sbt project when sbt build definition files change
      Global / onChangedBuildSource := ReloadOnSourceChanges
    )

    val buildSettings = inThisBuild(
      Seq(
        organization := "com.daml.network",
        scalaVersion := scala_version
        // , scalacOptions += "-Ystatistics" // re-enable if you need to debug compile times
      )
    )

    buildSettings ++ globalSettings
  }

}
