import BuildCommon.sharedCantonSettings
import Dependencies._

/*
 * sbt-settings that will be shared between all CN apps.
 */

BuildCommon.sbtSettings

// sbt insists on these redeclarations
lazy val `canton-community-app` = BuildCommon.`canton-community-app`
lazy val `canton-community-common` = BuildCommon.`canton-community-common`
lazy val `canton-community-domain` = BuildCommon.`canton-community-domain`
lazy val `canton-community-participant` = BuildCommon.`canton-community-participant`
lazy val `canton-blake2b` = BuildCommon.`canton-blake2b`
lazy val `canton-functionmeta` = BuildCommon.`canton-functionmeta`
lazy val `canton-slick-fork` = BuildCommon.`canton-slick-fork`
lazy val `canton-daml-fork` = BuildCommon.`canton-daml-fork`
lazy val `canton-wartremover-extension` = BuildCommon.`canton-wartremover-extension`

inThisBuild(
  List(
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalafixDependencies ++= List(
      "com.github.liancheng" %% "organize-imports" % "0.6.0"
    ),
  )
)

/*
 * Root project
 */
lazy val root = (project in file("."))
  .aggregate(
    `apps-common`,
    `apps-validator`,
    `apps-app`,
    `canton-community-common`,
    `canton-blake2b`,
    `canton-slick-fork`,
    `canton-daml-fork`,
    `canton-functionmeta`,
    `canton-wartremover-extension`,
    `canton-community-app`,
    `canton-community-domain`,
    `canton-community-participant`,
  )
  .settings(BuildCommon.sharedSettings, scalacOptions += "-Wconf:src=src_managed/.*:silent")

lazy val `apps-common` =
  project
    .in(file("apps/common"))
    // make Canton code available to CC repo
    .dependsOn(`canton-community-common`, `canton-community-app`)
    .settings(BuildCommon.sharedSettings, BuildCommon.cantonWarts)

lazy val `apps-validator` =
  project
    .in(file("apps/validator"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedSettings,
      BuildCommon.cantonWarts,
      Compile / PB.targets := Seq(
        scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
      ),
      Compile / PB.protoSources ++= (Test / PB.protoSources).value,
      scalacOptions += "-Wconf:src=src_managed/.*:silent",
    )

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    // make Canton code available to CC repo
    .dependsOn(`apps-validator`)
    .settings(BuildCommon.sharedSettings, BuildCommon.cantonWarts)
