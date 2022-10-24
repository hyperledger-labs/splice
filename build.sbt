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
    `apps-scan`,
    `apps-aaa-splitwise`,
    `apps-svc`,
    `apps-app`,
    `apps-wallet`,
    `apps-wallet-daml`,
    `apps-directory`,
    // `apps-frontends`, // For now we are not auto-compiling frontends, until there is proper caching there
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
  .settings(
    BuildCommon.sharedSettings,
    scalacOptions += "-Wconf:src=src_managed/.*:silent",
    // Needed to be able to resolve scalafmt snapshot versions
    resolvers += Resolver.sonatypeRepo("snapshots"),
  )

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
        java_jwt,
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
      `apps-wallet-daml` % "compile->compile;test->test",
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

lazy val tsCodegen = taskKey[Seq[File]]("generate typescript for the daml models")

// Returns a Seq[File] so that it can be reused both in tsCodegen and in sourceGenerators.
// However, we return an empty Seq because we don't want sbt to be smart and actually do anything with these generated files.
lazy val tsCodegenTask: Def.Initialize[Task[Seq[File]]] = Def.task {
  val log = streams.value.log
  val dars =
    Seq(
      (`apps-common` / Compile / damlBuild).value,
      (`apps-wallet-daml` / Compile / damlBuild).value,
      (`apps-directory` / Compile / damlBuild).value,
      (`apps-aaa-splitwise` / Compile / damlBuild).value,
    )
  val damlJs = s"${baseDirectory.value}/daml.js"
  new java.io.File(damlJs).delete()
  val args = dars.flatten ++ Seq("-o", damlJs)
  val _ = runCommand(s"daml2ts ${args.mkString(" ")}", log)
  Seq()
}

lazy val `apps-common-frontend` = {
  project
    .in(file("apps/common/frontend"))
    .dependsOn(
      `apps-common`,
      `apps-directory`,
      `apps-wallet`,
      `apps-aaa-splitwise`,
    )
    .settings(
      // TODO(i1216): Leaving for now both tsCodegen and compile. If they end up remaining identical, then tsCodegen will be removed.
      tsCodegen := tsCodegenTask.value,
      Compile / sourceGenerators += tsCodegen.taskValue,
      cleanFiles += baseDirectory.value / "daml.js",
    )
}

// TODO(i1216): The frontend val's below ended up being not required for now, but they will be, so leaving them as placeholders for now
lazy val `apps-wallet-frontend` = {
  project
    .in(file(s"apps/wallet/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings()
}

lazy val `apps-splitwise-frontend` = {
  project
    .in(file(s"apps/splitwise/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings()
}

lazy val `apps-directory-frontend` = {
  project
    .in(file(s"apps/directory/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings()
}

lazy val `apps-frontends` = {
  project.aggregate(
    `apps-common-frontend`,
    `apps-wallet-frontend`,
    `apps-directory-frontend`,
    `apps-splitwise-frontend`,
  )
}

lazy val `apps-wallet-daml` =
  project
    .in(file("apps/wallet/daml"))
    .dependsOn(
    )
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.sharedAppSettings,
      libraryDependencies ++= Seq(daml_bindings_scala),
      Compile / damlDependencies := (`apps-common` / Compile / damlBuild).value,
      Compile / damlSourceDirectory := file("apps/wallet/daml"),
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml",
      Test / damlSourceDirectory := file("apps/wallet/daml"),
      Compile / damlDarOutput := file("apps/wallet/daml") / ".daml" / "dist",
      BuildCommon.damlCodegenSettings,
      BuildCommon.copyDarResources,
    )

lazy val `apps-wallet` =
  project
    .in(file("apps/wallet"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `apps-validator` % "compile->compile;test->test",
      `apps-wallet-daml` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )

lazy val `apps-directory` =
  project
    .in(file("apps/directory"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-wallet` % "compile->compile;test->test",
    )
    .enablePlugins(DamlPlugin)
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      Compile / damlDependencies := (`apps-wallet-daml` / Compile / damlBuild).value,
      Compile / damlSourceDirectory := file("apps/directory/daml"),
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml",
      Test / damlSourceDirectory := (Compile / damlSourceDirectory).value,
      Compile / damlDarOutput := file("apps/directory/daml") / ".daml" / "dist",
      BuildCommon.damlCodegenSettings,
      BuildCommon.copyDarResources,
    )

// IntelliJ just sorts dependencies in alphabetical order, and splitwise needs to come first in the classpath
// as it is the only one containing the .class files from the daml->scala codegen.
// TODO(#592): change the splitwise module to a better name once we have improved our daml->scala codegen usage
lazy val `apps-aaa-splitwise` =
  project
    .in(file("apps/splitwise"))
    .enablePlugins(DamlPlugin)
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-directory` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      Compile / damlDependencies := (`apps-directory` / Compile / damlBuild).value,
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
      case None => Left(s"None of the codegened files originate from directory: ${files}")
      case Some(f) => Right(Seq((f, path)))
    }
  }
}

def isScalaCodegenFile(fileName: String): Boolean = {
  fileName match {
    case PathList("com", "daml", "network", "codegen", _*) => true
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
    val webUis =
      Seq(
        ("apps/wallet/frontend/build", "wallet"),
        ("apps/directory/frontend/build", "directory"),
        ("apps/splitwise/frontend/build", "splitwise"),
      )
    val dars =
      Seq(
        (`apps-common` / Compile / damlBuild).value,
        (`apps-wallet-daml` / Compile / damlBuild).value,
        (`apps-aaa-splitwise` / Compile / damlBuild).value,
      )
    val args = examples ++ webUis.flatMap({ case (source, name) =>
      Seq("-r", source, s"web-uis/$name")
    }) ++ dars.flatten.flatMap({ case dar =>
      Seq("-r", dar, s"dars/${dar.getName}")
    })
    runCommand(s"bash ./create-bundle.sh $assemblyJar ${args.mkString(" ")}", log)
  }
}

lazy val cleanCnDars = taskKey[Unit]("Remove all `.dar` files in `apps` and `canton-coin`")
cleanCnDars := {
  val log = streams.value.log
  runCommand(s"find apps -name *.dar -delete", log)
  runCommand(s"find canton-coin -name *.dar -delete", log)
}

lazy val checkErrors = taskKey[Unit]("Check test log for errors and fail if there is one")
checkErrors := {
  import scala.sys.process._
  val res =
    Seq(
      ".circleci/canton-scripts/check-logs.sh",
      "log/canton_test.log",
      "project/errors-in-log-to-ignore.txt",
    ).!
  if (res != 0) {
    sys.error("canton_test.log contains problems.")
  }
}

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    // make Canton code available to CC repo
    .dependsOn(
      // Splitwise needs to come first so that the codegened files
      // come first in the classpath. Otherwise, you get NoSuchMethod errors at runtime.
      `apps-aaa-splitwise`,
      `apps-directory`,
      `apps-validator`,
      `apps-svc`,
      `apps-scan`,
      `apps-wallet`,
      `apps-wallet-daml`,
      `canton-community-app` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies += "org.scalatestplus" %% "selenium-4-4" % "3.2.14.0" % "test",
      libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "4.5.0" % "test",
      BuildCommon.sharedAppSettings,
      BuildCommon.cantonWarts,
      bundleTask,
      assembly / test := {}, // don't run tests during assembly
      // when building the fat jar, we need to properly merge our artefacts
      assembly / assemblyMergeStrategy := mergeStrategy((assembly / assemblyMergeStrategy).value),
      assembly / mainClass := Some("com.daml.network.CoinApp"),
      assembly / assemblyJarName := s"coin-${version.value}.jar",
    )
