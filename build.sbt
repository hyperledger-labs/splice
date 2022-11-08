import BuildCommon.{runCommand, sharedCantonSettings}
import Dependencies._
import DamlPlugin.autoImport._
import BuildCommon.defs._
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
    `apps-splitwise`,
    `apps-svc`,
    `apps-app`,
    `apps-wallet`,
    `apps-wallet-daml`,
    `apps-directory`,
    `apps-frontends`,
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
        jwks_rsa,
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

lazy val `apps-common-frontend` = {
  project
    .in(file("apps/common/frontend"))
    .dependsOn(
      `apps-common`,
      `apps-directory`,
      `apps-wallet`,
      `apps-splitwise`,
    )
    .settings(
      // daml typescript code generation settings:
      damlTsCodegenSources :=
        (`apps-common` / Compile / damlBuild).value ++
          (`apps-wallet-daml` / Compile / damlBuild).value ++
          (`apps-directory` / Compile / damlBuild).value ++
          (`apps-splitwise` / Compile / damlBuild).value,
      damlTsCodegenDir := baseDirectory.value / "daml.js",
      damlTsCodegen := BuildCommon.damlTsCodegenTask.value,
      // npm install settings:
      npmPackageFiles := Seq(baseDirectory.value / "package.json"),
      npmInstall := BuildCommon.npmInstallTask.value,
      npmRootDir := baseDirectory.value / "../..",
      Compile / compile := {
        damlTsCodegen.value
        npmInstall.value
        (Compile / compile).value
      },
      bundle := {
        (Compile / compile).value
        (`apps-common-frontend-protobuf` / Compile / compile).value
        val log = streams.value.log
        runCommand(
          s"npm run build --workspace common-frontend",
          log,
          None,
          Some(npmRootDir.value),
        )
        baseDirectory.value / "lib"
      },
      // We could support npmLint and npmFix at the individual project level, but right now that doesn't seem very useful
      // so we just do it once for all workspaces here.
      npmLint := {
        val log = streams.value.log
        runCommand(
          s"npm run check --workspaces",
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmFix := {
        val log = streams.value.log
        runCommand(
          s"npm run fix --workspaces",
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      cleanFiles += damlTsCodegenDir.value,
      cleanFiles += baseDirectory.value / "lib",
      cleanFiles += baseDirectory.value / "../../node_modules",
    )
}

/** Common settings to be used for frontends. Requires settings commonFrontendBundle and frontendWorkspace to be specified.
  */
lazy val sharedFrontendSettings: Seq[Setting[_]] = Seq(
  (`apps-common-frontend` / npmPackageFiles) += baseDirectory.value / "package.json",
  bundle := BuildCommon.bundleFrontend.value,
  cleanFiles += baseDirectory.value / "build",
)
lazy val `apps-wallet-frontend` = {
  project
    .in(file("apps/wallet/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value,
      frontendWorkspace := "wallet-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-splitwise-frontend` = {
  project
    .in(file("apps/splitwise/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value,
      frontendWorkspace := "splitwise-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-directory-frontend` = {
  project
    .in(file("apps/directory/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value,
      frontendWorkspace := "directory-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-common-frontend-protobuf` = {
  project
    .in(file("apps/common/frontend-protobuf"))
    .dependsOn(
      `apps-common` % "compile->protocGenerate",
      `apps-directory` % "compile->protocGenerate",
      `apps-wallet` % "compile->protocGenerate",
      `apps-splitwise` % "compile->protocGenerate",
      `apps-validator` % "compile->protocGenerate",
      `apps-scan` % "compile->protocGenerate",
    )
    .settings(
      Compile / sourceGenerators += Def.task {
        val log = streams.value.log
        runCommand(s"bash ${baseDirectory.value}/gen-ledger-api-proto.sh", log)
        Seq()
      }.taskValue,
      cleanFiles += baseDirectory.value / "com",
    )
}

lazy val `apps-frontends` = {
  project.aggregate(
    `apps-common-frontend-protobuf`,
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
      Compile / damlEnableScalaCodegen := false,
      BuildCommon.copyDarResources,
    )

lazy val `apps-splitwise` =
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
      Compile / damlDependencies := (`apps-wallet-daml` / Compile / damlBuild).value,
      Compile / damlSourceDirectory := file("apps/splitwise/daml"),
      cleanFiles += (Compile / damlSourceDirectory).value.getAbsoluteFile / ".daml",
      Test / damlSourceDirectory := (Compile / damlSourceDirectory).value,
      Compile / damlDarOutput := file("apps/splitwise/daml") / ".daml" / "dist",
      BuildCommon.damlCodegenSettings,
      Compile / damlEnableScalaCodegen := false,
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
      // Wallet depends on CC model.
      case f if sourceOfFileForMerge(tempDir, f)._1.getPath().contains("wallet") => f
    }
    result match {
      case None => Left(s"None of the codegened files originate from wallet: ${files}")
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

def isJavaCodegenFile(fileName: String): Boolean = {
  fileName match {
    case PathList("com", "daml", "network", "codegen", "java", _*) => true
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
    case path if isJavaCodegenFile(path) => MergeStrategy.deduplicate
    case path if isScalaCodegenFile(path) => scalaCodegenStrategy
    // Dedup between ledger-api-java-proto (pulled in via Scala bindings)
    // and the copy of that inlined into bindings-java.
    case PathList("com", "daml", "ledger", "api", "v1", _*) => MergeStrategy.first
    case x => oldStrategy(x)
  }
}

import sbtassembly.AssemblyPlugin.autoImport.assembly

/** Generate a release bundle. Simplified versions of Canton's release bundling (see Canton's code base / issue #147) */
lazy val bundleTask = {
  bundle := {
    val log = streams.value.log
    val assemblyJar = assembly.value
    val examples = Seq("-c", "apps/app/src/pack")
    val webUis =
      Seq(
        ((`apps-wallet-frontend` / bundle).value, "wallet"),
        ((`apps-directory-frontend` / bundle).value, "directory"),
        ((`apps-splitwise-frontend` / bundle).value, "splitwise"),
      )
    val dars =
      Seq(
        (`apps-common` / Compile / damlBuild).value,
        (`apps-wallet-daml` / Compile / damlBuild).value,
        (`apps-splitwise` / Compile / damlBuild).value,
      )
    val args = examples ++ webUis.flatMap({ case (source, name) =>
      Seq("-r", source, s"web-uis/$name")
    }) ++ dars.flatten.flatMap({ case dar =>
      Seq("-r", dar, s"dars/${dar.getName}")
    })
    runCommand(
      s"bash ./create-bundle.sh $assemblyJar ${(assembly / mainClass).value.get} ${args.mkString(" ")}",
      log,
    )
    assemblyJar
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

// https://tanin.nanakorn.com/technical/2018/09/10/parallelise-tests-in-sbt-on-circle-ci.html
// also used by Canton team
lazy val printTests = taskKey[Unit](
  "write full class names of `apps-app` tests to `test-full-class-names.log`; used for CI test splitting"
)
printTests := {
  import java.io._
  println("Appending full class names of tests to the file `test-full-class-names.log`.")
  val pw = new PrintWriter(new FileWriter(s"test-full-class-names.log", true))
  val tmp = (`apps-app` / Test / definedTests).value
  tmp.sortBy(_.name).foreach { t =>
    pw.println(t.name)
  }
  pw.close()
}

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    // make Canton code available to CC repo
    .dependsOn(
      // Wallet needs to come first so that the codegened files
      // come first in the classpath. Otherwise, you get NoSuchMethod errors at runtime.
      `apps-wallet-daml`,
      `apps-splitwise`,
      `apps-directory`,
      `apps-validator`,
      `apps-svc`,
      `apps-scan`,
      `apps-wallet`,
      `canton-community-app` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies += "org.scalatestplus" %% "selenium-4-4" % "3.2.14.0" % "test",
      libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "4.6.0" % "test",
      libraryDependencies += "eu.rekawek.toxiproxy" % "toxiproxy-java" % "2.1.4" % "test",
      BuildCommon.sharedAppSettings,
      BuildCommon.cantonWarts,
      bundleTask,
      assembly / test := {}, // don't run tests during assembly
      // when building the fat jar, we need to properly merge our artefacts
      assembly / assemblyMergeStrategy := mergeStrategy((assembly / assemblyMergeStrategy).value),
      assembly / mainClass := Some("com.daml.network.CoinApp"),
      assembly / assemblyJarName := s"coin-${version.value}.jar",
    )
