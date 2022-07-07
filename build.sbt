import BuildCommon.{runCommand, sharedCantonSettings}
import Dependencies._
import DamlPlugin.autoImport._
import sbtassembly.{MergeStrategy, PathList}

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
    `apps-svc`,
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
    .dependsOn(`canton-community-common`, `canton-community-app` % "compile->compile;test->test")
    .enablePlugins(DamlPlugin)
    .settings(
        BuildCommon.sharedSettings,
        BuildCommon.cantonWarts,
        /* The reason we have to specify these items explicitly is that the DamlPlugin expects a gradle
         * `src/main/daml` directory structure. Instead we have the classical SDK `./daml` structure.
         * We output the dar to the usual `.daml/dist` dir because that's where a naive user expects it.
         */
        Compile / damlSourceDirectory := file("canton-coin"),
        Compile / damlDarOutput := file("canton-coin") / ".daml" / "dist",
        Compile / damlCodeGeneration := Seq(
          (
            (Compile / damlSourceDirectory).value / "daml" / "CC",
            (Compile / damlDarOutput).value / "canton-coin",
            "com.digitalasset.network"
          )
        )
    )

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

lazy val `apps-svc` =
  project
    .in(file("apps/svc"))
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

// Copied from Canton. Can probably be removed once we use Canton as a library.
def mergeStrategy(oldStrategy: String => MergeStrategy): String => MergeStrategy = {
  {
    case PathList("META-INF", "io.netty.versions.properties") => MergeStrategy.first
    case "reflect.properties" => MergeStrategy.first
    case PathList("org", "checkerframework", _ @_*) => MergeStrategy.first
    case PathList("google", "protobuf", _*) => MergeStrategy.first
    case PathList("org", "apache", "logging", _*) => MergeStrategy.first
    case PathList("ch", "qos", "logback", _*) => MergeStrategy.first
    case PathList(
          "META-INF",
          "org",
          "apache",
          "logging",
          "log4j",
          "core",
          "config",
          "plugins",
          "Log4j2Plugins.dat",
        ) =>
      MergeStrategy.first
    case "META-INF/versions/9/module-info.class" => MergeStrategy.discard
    case path if path.contains("module-info.class") => MergeStrategy.discard
    case PathList("org", "jline", _ @_*) => MergeStrategy.first
    case x => oldStrategy(x)
  }
}

import sbtassembly.AssemblyPlugin.autoImport.assembly

/** Generate a release bundle. Simplified versions of Canton's release bundling (see Canton's code base / issue #147) */
lazy val bundleTask = {
  lazy val bundle = taskKey[Unit]("create a release bundle")
  bundle := {
    val log = streams.value.log
    val assemblyJar = assembly.value
    val examples = Seq("-c", "apps/app/src/pack")
    runCommand(s"bash ./create-bundle.sh $assemblyJar ${examples.mkString(" ")}", log)
  }
}

lazy val appSettings = Seq(
  bundleTask,
  assembly / test := {}, // don't run tests during assembly
  // when building the fat jar, we need to properly merge our artefacts
  assembly / assemblyMergeStrategy := mergeStrategy((assembly / assemblyMergeStrategy).value),
  assembly / mainClass := Some("com.daml.network.CoinApp"),
  assembly / assemblyJarName := s"coin-${version.value}.jar",
)

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    // make Canton code available to CC repo
    .dependsOn(
      `apps-validator`,
      `apps-svc`,
      `canton-community-app` % "compile->compile;test->test",
    )
    .settings(
      BuildCommon.sharedSettings,
      BuildCommon.cantonWarts,
      appSettings,
    )
