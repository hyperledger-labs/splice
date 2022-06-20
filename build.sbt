import Dependencies._

/*
 * sbt-settings that will be shared between all CN apps.
 */

BuildCommon.sbtSettings

lazy val sharedSettings: Seq[Def.Setting[_]] = Seq(
  libraryDependencies += scalatest % Test
)

/*
 * Root project
 */
lazy val root = (project in file("."))
  .aggregate(`apps-common`, `apps-validator`)
  .settings(sharedSettings, scalacOptions += "-Wconf:src=src_managed/.*:silent")

lazy val `apps-common` =
  project.in(file("apps/common")).settings(sharedSettings)

lazy val `apps-validator` =
  project
    .in(file("apps/validator"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      sharedSettings,
      Compile / PB.targets := Seq(
        scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
      ),
      Compile / PB.protoSources ++= (Test / PB.protoSources).value,
      scalacOptions += "-Wconf:src=src_managed/.*:silent",
    )
