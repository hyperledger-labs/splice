import BuildCommon.{runCommand, sharedCantonSettings}
import Dependencies._
import DamlPlugin.autoImport._
import sbtassembly.{AssemblyUtils, MergeStrategy, PathList}

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
    `apps-splitwise`,
    `apps-svc`,
    `apps-app`,
    `apps-wallet`,
    `apps-directory-provider`,
    `apps-directory-user`,
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
      libraryDependencies ++= Seq(
        scalapb_runtime_grpc,
        scalapb_runtime,
        daml_ledger_api_scalapb,
        daml_ledger_api_proto % "protobuf",
      ),
      BuildCommon.sharedAppSettings,
      /* The reason we have to specify these items explicitly is that the DamlPlugin expects a gradle
       * `src/main/daml` directory structure. Instead we have the classical SDK `./daml` structure.
       * We output the dar to the usual `.daml/dist` dir because that's where a naive user expects it.
       */
      Compile / damlSourceDirectory := file("canton-coin"),
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml",
      Test / damlSourceDirectory := file("canton-coin"),
      Compile / damlDarOutput := file("canton-coin") / ".daml" / "dist",
      BuildCommon.damlCodegenSettings,
      BuildCommon.copyDarResources,
    )

lazy val `apps-validator` =
  project
    .in(file("apps/validator"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )

lazy val `apps-svc` =
  project
    .in(file("apps/svc"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )

lazy val `apps-scan` =
  project
    .in(file("apps/scan"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )

lazy val `apps-wallet` =
  project
    .in(file("apps/wallet"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
    )
    .enablePlugins(DamlPlugin)
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      Compile / damlDependencies := (`apps-common` / Compile / damlBuild).value,
      BuildCommon.sharedAppSettings,
      Compile / damlSourceDirectory := file("apps/wallet/daml"),
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml",
      Test / damlSourceDirectory := file("apps/wallet/daml"),
      Compile / damlDarOutput := file("apps/wallet/daml") / ".daml" / "dist",
      BuildCommon.damlCodegenSettings,
      BuildCommon.copyDarResources,
    )

lazy val `apps-directory-provider` =
  project
    .in(file("apps/directory-provider"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-wallet` % "compile->compile;test->test",
    )
    .enablePlugins(DamlPlugin)
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      Compile / damlDependencies := (`apps-wallet` / Compile / damlBuild).value,
      Compile / damlSourceDirectory := file("apps/directory-provider/daml"),
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml",
      Test / damlSourceDirectory := (Compile / damlSourceDirectory).value,
      Compile / damlDarOutput := file("apps/directory-provider/daml") / ".daml" / "dist",
      BuildCommon.damlCodegenSettings,
    )

lazy val `apps-directory-user` =
  project
    .in(file("apps/directory-user"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-directory-provider` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )

lazy val `apps-splitwise` =
  project
    .in(file("apps/splitwise"))
    .enablePlugins(DamlPlugin)
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-directory-provider` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      Compile / damlDependencies := (`apps-directory-provider` / Compile / damlBuild).value,
      Compile / damlSourceDirectory := file("apps/splitwise/daml"),
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml",
      Test / damlSourceDirectory := (Compile / damlSourceDirectory).value,
      Compile / damlDarOutput := file("apps/splitwise/daml") / ".daml" / "dist",
      BuildCommon.damlCodegenSettings,
    )

val scalaCodegenStrategy = new MergeStrategy {
  // Copied from SBT because they forgot to make it public
  // https://github.com/sbt/sbt-assembly/issues/435
  val PathRE = "([^/]+)/(.*)".r
  def sourceOfFileForMerge(tempDir: File, f: File): (File, File, String, Boolean) = {
    val baseURI = tempDir.getCanonicalFile.toURI
    val otherURI = f.getCanonicalFile.toURI
    val relative = baseURI.relativize(otherURI)
    val PathRE(head, tail) = relative.getPath
    val base = tempDir / head

    if ((tempDir / (head + ".jarName")) exists) {
      val jarName = IO.read(tempDir / (head + ".jarName"), IO.utf8)
      (new File(jarName), base, tail, true)
    } else {
      val dirName = IO.read(tempDir / (head + ".dir"), IO.utf8)
      (new File(dirName), base, tail, false)
    } // if-else
  }
  val name = "scala codegen strat"
  def apply(tempDir: File, path: String, files: Seq[File]): Either[String, Seq[(File, String)]] = {
    val result = files.collectFirst {
      // Splitwise depends on everything so we can safely
      // chose that.
      case f if sourceOfFileForMerge(tempDir, f)._1.getPath().contains("splitwise") => f
    }
    result match {
      case None => Left(s"None of the codegened files originate from directory-provider: ${files}")
      case Some(f) => Right(Seq((f, path)))
    }
  }
}

def isScalaCodegenFile(fileName: String): Boolean = {
  fileName match {
    // We codegen into com.digitalasset while our source is in com.daml so
    // we can detect it that way.
    case PathList("com", "digitalasset", "network", _*) => true
    case _ => false
  }
}

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
    case path if isScalaCodegenFile(path) => scalaCodegenStrategy
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

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    // make Canton code available to CC repo
    .dependsOn(
      // Directory provider needs to come first so that the codegened files
      // come first in the classpath. Otherwise, you get NoSuchMethod errors at runtime.
      `apps-directory-provider`,
      `apps-directory-user`,
      `apps-validator`,
      `apps-splitwise`,
      `apps-svc`,
      `apps-scan`,
      `apps-wallet`,
      `canton-community-app` % "compile->compile;test->test",
    )
    .settings(
      BuildCommon.sharedAppSettings,
      BuildCommon.cantonWarts,
      bundleTask,
      assembly / test := {}, // don't run tests during assembly
      // when building the fat jar, we need to properly merge our artefacts
      assembly / assemblyMergeStrategy := mergeStrategy((assembly / assemblyMergeStrategy).value),
      assembly / mainClass := Some("com.daml.network.CoinApp"),
      assembly / assemblyJarName := s"coin-${version.value}.jar",
    )
