import sbt.Keys._
import sbt._
import Dependencies._

object BuildCommon {

  lazy val sbtSettings: Seq[Def.Setting[_]] = {
    val buildSettings = inThisBuild(
      Seq(
        organization := "com.daml.network",
        scalaVersion := scala_version
        // , scalacOptions += "-Ystatistics" // re-enable if you need to debug compile times
      )
    )

    buildSettings
  }

}
