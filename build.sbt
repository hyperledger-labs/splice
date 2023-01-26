import BuildUtil.runCommand
import Dependencies._
import DamlPlugin.autoImport._
import BuildCommon.defs._
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
lazy val `canton-research-services` = BuildCommon.`canton-research-services`
lazy val `canton-blake2b` = BuildCommon.`canton-blake2b`
lazy val `canton-functionmeta` = BuildCommon.`canton-functionmeta`
lazy val `canton-slick-fork` = BuildCommon.`canton-slick-fork`
lazy val `canton-daml-fork` = BuildCommon.`canton-daml-fork`
lazy val `canton-wartremover-extension` = BuildCommon.`canton-wartremover-extension`
lazy val `canton-util-external` = BuildCommon.`canton-util-external`
lazy val `canton-util-internal` = BuildCommon.`canton-util-internal`
lazy val `canton-akka-fork` = BuildCommon.`canton-akka-fork`

inThisBuild(
  List(
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
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
    `apps-sv`,
    `apps-app`,
    `apps-wallet`,
    `apps-directory`,
    `apps-frontends`,
    `cn-util-daml`,
    `canton-coin-api-daml`,
    `canton-coin-daml`,
    `wallet-payments-daml`,
    `wallet-daml`,
    `directory-daml`,
    `splitwise-daml`,
    `svc-governance-daml`,
    `canton-community-common`,
    `canton-research-services`,
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

// Shared non-template/non-interface code
// used across our DARs.
lazy val `cn-util-daml` =
  project
    .in(file("daml/cn-util"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings
    )

lazy val `canton-coin-api-daml` =
  project
    .in(file("daml/canton-coin-api"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings
    )

lazy val `canton-coin-daml` =
  project
    .in(file("daml/canton-coin"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value ++
          (`canton-coin-api-daml` / Compile / damlBuild).value,
    )

lazy val `svc-governance-daml` =
  project
    .in(file("daml/svc-governance"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value ++
          (`canton-coin-daml` / Compile / damlBuild).value ++
          (`canton-coin-api-daml` / Compile / damlBuild).value,
    )

// This defines the Daml model that we expose to app developers
// to manage payments through the wallet.
lazy val `wallet-payments-daml` =
  project
    .in(file("daml/wallet-payments"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value ++
          (`canton-coin-api-daml` / Compile / damlBuild).value,
    )

// This defines the Daml model that we do not expose to app devs
// but do use internally, e.g., for batching.
lazy val `wallet-daml` =
  project
    .in(file("daml/wallet"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`canton-coin-daml` / Compile / damlBuild).value ++ (`wallet-payments-daml` / Compile / damlBuild).value,
    )

lazy val `directory-daml` =
  project
    .in(file("daml/directory-service"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`wallet-daml` / Compile / damlBuild).value,
    )

lazy val `splitwise-daml` =
  project
    .in(file("daml/splitwise"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`wallet-daml` / Compile / damlBuild).value,
    )

lazy val `apps-common` =
  project
    .in(file("apps/common"))
    .dependsOn(
      `canton-community-common`,
      `canton-community-app` % "compile->compile;test->test",
      `canton-coin-daml`,
      `canton-research-services`,
    )
    .enablePlugins(BuildInfoPlugin)
    .settings(
      libraryDependencies ++= Seq(
        Dependencies.daml_lf_value_json,
        scalapb_runtime_grpc,
        scalapb_runtime,
        daml_ledger_api_scalapb,
        daml_ledger_api_proto % "protobuf",
        java_jwt,
        jwks_rsa,
        spray_json,
        akka_spray_json,
      ),
      BuildCommon.sharedAppSettings,
      buildInfoKeys := Seq[BuildInfoKey](
        BuildInfoKey(
          "compiledVersion",
          BuildUtil.runCommandOptionalLog(Seq("./build-tools/version-gen")),
        )
      ),
      buildInfoPackage := "com.daml.network.environment",
      buildInfoObject := "BuildInfo",
      Compile / guardrailTasks :=
        List(
          ScalaModels(
            new File("apps/common/src/main/openapi/common.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          )
        ),
    )

lazy val `apps-validator` =
  project
    .in(file("apps/validator"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `wallet-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(akka_http_cors, scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/validator/src/main/openapi/validator.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
            customExtraction = true,
          ),
          ScalaClient(
            new File("apps/validator/src/main/openapi/validator.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
        ),
      Compile / sourceGenerators += Def.task {
        import better.files.*
        import _root_.io.circe.*
        import _root_.io.circe.parser.*
        import _root_.io.circe.optics.JsonPath.*
        import _root_.io.circe.optics.JsonPath.{root => jsonRoot}
        import _root_.io.circe.syntax._

        val log = streams.value.log
        val cacheDir = streams.value.cacheDirectory
        val commonSpec = baseDirectory.value / "../../common/src/main/openapi/common.yaml"
        val validatorSpec = baseDirectory.value / "src/main/openapi/validator.yaml"
        val cache = FileFunction.cached(cacheDir) { _ =>
          runCommand(
            Seq(
              "openapi-generator-cli",
              "generate",
              "-g",
              "typescript",
              "-p",
              "npmName=validator-openapi",
              "-p",
              "npmName=validator-openapi",
              "-p",
              "moduleName=validator-openapi",
              "-p",
              "projectName=validator-openapi",
              "-p",
              "useTags=true",
              "-i",
              validatorSpec.toString,
              "-o",
              "apps/validator/frontend-openapi",
            ),
            log,
          )

          // Add empty check task to make npm happy
          val packageJson = File("apps/validator/frontend-openapi/package.json")
          val packageJsonContent = packageJson.contentAsString
          val doc: Json =
            parse(packageJsonContent).getOrElse(sys.error("Failed to parse package.json"))
          val updated = jsonRoot.scripts.obj.modify((obj: JsonObject) =>
            obj.add("check", "echo '[validator-frontend-openapi] no-op'".asJson)
          )(doc)
          packageJson.overwrite(updated.spaces2)
          ((baseDirectory.value ** "*") --- ((baseDirectory.value / "target" +++ baseDirectory.value / "dist") ** "*")).get.toSet
        }
        cache(Set(validatorSpec, commonSpec))
        // We need to return an empty Seq here, otherwise SBT tries to compile the typescript files as Scala files.
        Seq()
      }.taskValue,
    )

lazy val `apps-svc` =
  project
    .in(file("apps/svc"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )
    .dependsOn(
      `svc-governance-daml`
    )

lazy val `apps-sv` =
  project
    .in(file("apps/sv"))
    .dependsOn(`apps-common` % "compile->compile;test->test", `svc-governance-daml`)
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )
    .dependsOn(
      `apps-svc`
    )

lazy val `apps-scan` =
  project
    .in(file("apps/scan"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/scan/src/main/openapi/scan.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
          ScalaClient(
            new File("apps/scan/src/main/openapi/scan.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
        ),
    )

lazy val `apps-common-frontend` = {
  project
    .in(file("apps/common/frontend"))
    .dependsOn(
      `apps-common`,
      `apps-directory`,
      `apps-wallet`,
      `apps-splitwise`,
      `apps-validator`,
    )
    .settings(
      // daml typescript code generation settings:
      damlTsCodegenSources :=
        (`canton-coin-api-daml` / Compile / damlBuild).value ++
          (`canton-coin-daml` / Compile / damlBuild).value ++
          (`wallet-daml` / Compile / damlBuild).value ++
          (`wallet-payments-daml` / Compile / damlBuild).value ++
          (`directory-daml` / Compile / damlBuild).value ++
          (`splitwise-daml` / Compile / damlBuild).value,
      damlTsCodegenDir := baseDirectory.value / "daml.js",
      damlTsCodegen := BuildCommon.damlTsCodegenTask.value,
      // npm install settings:
      npmInstallDeps := baseDirectory.value / "package.json" +: damlTsCodegen.value,
      npmInstall := BuildCommon.npmInstallTask.value,
      npmRootDir := baseDirectory.value / "../..",
      Compile / compile := {
        npmInstall.value
        (Compile / compile).value
      },
      bundle := {
        (Compile / compile).value
        (`apps-common-frontend-protobuf` / Compile / compile).value
        (`apps-common-frontend-openapi` / Compile / compile).value
        (`apps-validator` / Compile / compile).value
        val log = streams.value.log
        val cacheDir = streams.value.cacheDirectory
        val sourceFiles =
          (baseDirectory.value ** ("*.tsx" || "*.ts" || "*.js" || "*.json") --- baseDirectory.value / "lib" ** "*" --- baseDirectory.value / "node_modules" ** "*").get.toSet
        val cache =
          FileFunction.cached(cacheDir) { _ =>
            // openapi-generator-cli only generates .ts files so we need to
            // compile to get .d.ts and .js files. We cannot run this as part of
            // apps-common-frontend-openapi/compile because that does not yet run
            // npm install.
            runCommand(
              Seq("npm", "run", "build", "--workspace", "common/frontend-openapi"),
              log,
              None,
              Some(npmRootDir.value),
            )
            runCommand(
              Seq("npm", "run", "build", "--workspace", "common-frontend"),
              log,
              None,
              Some(npmRootDir.value),
            )
            runCommand(
              Seq("npm", "run", "build", "--workspace", "validator/frontend-openapi"),
              log,
              None,
              Some(npmRootDir.value),
            )
            (baseDirectory.value / "lib" ** "*").get.toSet
          }
        (baseDirectory.value / "lib", cache(sourceFiles))
      },
      // We could support npmLint and npmFix at the individual project level, but right now that doesn't seem very useful
      // so we just do it once for all workspaces here.
      npmLint := {
        val log = streams.value.log
        runCommand(
          Seq("npm", "run", "check", "--workspaces"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmFix := {
        val log = streams.value.log
        runCommand(
          Seq("npm", "run", "fix", "--workspaces"),
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
  bundle := BuildCommon.bundleFrontend.value,
  cleanFiles += baseDirectory.value / "build",
  cleanFiles += baseDirectory.value / "node_modules",
)
lazy val `apps-wallet-frontend` = {
  project
    .in(file("apps/wallet/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "wallet-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-splitwise-frontend` = {
  project
    .in(file("apps/splitwise/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "splitwise-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-directory-frontend` = {
  project
    .in(file("apps/directory/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "directory-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-common-frontend-openapi` = {
  project
    .in(file("apps/common/frontend-openapi"))
    .settings(
      Compile / sourceGenerators += Def.task {
        import better.files.*
        import _root_.io.circe.*
        import _root_.io.circe.parser.*
        import _root_.io.circe.optics.JsonPath.*
        import _root_.io.circe.optics.JsonPath.{root => jsonRoot}
        import _root_.io.circe.syntax._

        val log = streams.value.log
        val cacheDir = streams.value.cacheDirectory
        val commonSpec = baseDirectory.value / "../../common/src/main/openapi/common.yaml"
        val directorySpec = baseDirectory.value / "../../directory/src/main/openapi/directory.yaml"
        val cache = FileFunction.cached(cacheDir) { _ =>
          runCommand(
            Seq(
              "openapi-generator-cli",
              "generate",
              "-g",
              "typescript",
              "-p",
              "npmName=common-openapi",
              "-p",
              "npmName=common-openapi",
              "-p",
              "moduleName=common-openapi",
              "-p",
              "projectName=common-openapi",
              "-p",
              "useTags=true",
              "-i",
              directorySpec.toString,
              "-o",
              "apps/common/frontend-openapi",
            ),
            log,
          )

          // Add empty check task to make npm happy
          val packageJson = File("apps/common/frontend-openapi/package.json")
          val packageJsonContent = packageJson.contentAsString
          val doc: Json =
            parse(packageJsonContent).getOrElse(sys.error("Failed to parse package.json"))
          val updated = jsonRoot.scripts.obj.modify((obj: JsonObject) =>
            obj.add("check", "echo '[common-protobuf] no-op'".asJson)
          )(doc)
          packageJson.overwrite(updated.spaces2)
          ((baseDirectory.value ** "*") --- ((baseDirectory.value / "target" +++ baseDirectory.value / "dist") ** "*")).get.toSet
        }
        cache(Set(directorySpec, commonSpec))
        // We need to return an empty Seq here, otherwise SBT tries to compile the typescript files as Scala files.
        Seq()
      }.taskValue,
      cleanFiles += baseDirectory.value / "com",
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
        runCommand(Seq(s"${baseDirectory.value}/gen-ledger-api-proto.sh"), log)
        Seq()
      }.taskValue,
      cleanFiles += baseDirectory.value / "com",
    )
}

lazy val `apps-frontends` = {
  project.aggregate(
    `apps-common-frontend-protobuf`,
    `apps-common-frontend-openapi`,
    `apps-common-frontend`,
    `apps-wallet-frontend`,
    `apps-directory-frontend`,
    `apps-splitwise-frontend`,
  )
}

lazy val `apps-wallet` =
  project
    .in(file("apps/wallet"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `apps-validator` % "compile->compile;test->test",
      `wallet-daml`,
      // TODO (M3-18) Remove this once we have multi-domain interface support.
      `directory-daml`,
      `splitwise-daml`,
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
      `apps-scan` % "compile->compile;test->test",
      `wallet-daml`,
      `directory-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(
        scalapb_runtime_grpc,
        scalapb_runtime,
        akka_http_cors,
      ),
      BuildCommon.sharedAppSettings,
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/directory/src/main/openapi/directory.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
          ScalaClient(
            new File("apps/directory/src/main/openapi/directory.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
        ),
    )

lazy val `apps-splitwise` =
  project
    .in(file("apps/splitwise"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-directory` % "compile->compile;test->test",
      `splitwise-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
    )

// Copied from Canton. Can probably be removed once we use Canton as a library.
def mergeStrategy(oldStrategy: String => MergeStrategy): String => MergeStrategy = {
  {
    case PathList("buf.yaml") => MergeStrategy.discard
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
        (`canton-coin-daml` / Compile / damlBuild).value,
        (`wallet-daml` / Compile / damlBuild).value,
        (`splitwise-daml` / Compile / damlBuild).value,
        (`directory-daml` / Compile / damlBuild).value,
      )
    val args: Seq[String] = examples ++ webUis.flatMap({ case ((source, _), name) =>
      Seq[String]("-r", source.toString, s"web-uis/$name")
    }) ++ dars.flatten.flatMap({ case dar =>
      Seq[String]("-r", dar.toString, s"dars/${dar.getName}")
    })
    val cacheDir = streams.value.cacheDirectory
    val main = (assembly / mainClass).value.get
    val cache = FileFunction.cached(cacheDir) { _ =>
      runCommand(
        Seq[String](
          "./create-bundle.sh",
          assemblyJar.toString,
          main,
        ) ++ args,
        log,
      )
      val buildFiles = ((assemblyJar.getParentFile.getParentFile / "release") ** "*").get.toSet
      buildFiles
    }
    val sourceFiles =
      webUis.foldLeft(Set.empty[File]) { case (acc, ((_, buildFiles), _)) =>
        acc union buildFiles
      } ++
        dars.foldLeft(Set.empty[File]) { case (a, b) => a ++ b } +
        assemblyJar
    (assemblyJar, cache(sourceFiles))
  }
}

lazy val jsonnetfmtCheck = taskKey[Unit]("Check format of `.jsonnet` files`")
jsonnetfmtCheck := {
  val log = streams.value.log
  val files = baseDirectory.value ** "*.jsonnet"
  runCommand(Seq("jsonnetfmt", "--test", "--string-style", "d", "--") ++ files.getPaths, log)
}

lazy val jsonnetfmtFix = taskKey[Unit]("Format `.jsonnet` files`")
jsonnetfmtFix := {
  val log = streams.value.log
  val files = baseDirectory.value ** "*.jsonnet"
  runCommand(Seq("jsonnetfmt", "--in-place", "--string-style", "d", "--") ++ files.getPaths, log)
}

lazy val cleanCnDars = taskKey[Unit]("Remove all `.dar` files in `apps` and `canton-coin`")
cleanCnDars := {
  val log = streams.value.log
  runCommand(Seq("find", "apps", "-name", "*.dar", "-delete"), log)
  runCommand(Seq("find", "daml", "-name", "*.dar", "-delete"), log)
}

lazy val checkErrors = taskKey[Unit]("Check test log for errors and fail if there is one")
checkErrors := {
  import scala.sys.process._
  Seq("log/canton_test.log", "log/canton.log").foreach { log =>
    val res =
      Seq(
        ".circleci/canton-scripts/check-logs.sh",
        log,
        "project/errors-in-log-to-ignore.txt",
      ).!
    if (res != 0) {
      sys.error(s"$log contains problems.")
    }
  }
}

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    .dependsOn(
      `wallet-payments-daml`,
      `wallet-daml`,
      `apps-splitwise`,
      `apps-directory`,
      `apps-validator`,
      `apps-svc`,
      `apps-sv`,
      `apps-scan`,
      `apps-wallet`,
      `canton-coin-api-daml`,
      `canton-community-app` % "compile->compile;test->test",
    )
    .settings(
      libraryDependencies += "org.scalatestplus" %% "selenium-4-4" % "3.2.14.0" % "test",
      libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "4.6.0" % "test",
      libraryDependencies += "eu.rekawek.toxiproxy" % "toxiproxy-java" % "2.1.4" % "test",
      libraryDependencies += "com.auth0" % "auth0" % "1.44.1",
      // Force SBT to use the right version of opentelemetry libs.
      dependencyOverrides ++= Seq(
        CantonDependencies.opentelemetry_api,
        CantonDependencies.opentelemetry_context,
        CantonDependencies.opentelemetry_semconv,
        CantonDependencies.opentelemetry_sdk,
        CantonDependencies.opentelemetry_sdk_common,
        CantonDependencies.opentelemetry_sdk_autoconfigure,
        CantonDependencies.opentelemetry_sdk_logs,
        CantonDependencies.opentelemetry_sdk_trace,
        CantonDependencies.opentelemetry_sdk_metrics,
        CantonDependencies.opentelemetry_sdk_autoconfigure,
        CantonDependencies.opentelemetry_prometheus,
        CantonDependencies.opentelemetry_zipkin,
        CantonDependencies.opentelemetry_jaeger,
        CantonDependencies.opentelemetry_instrumentation_grpc,
      ),
      BuildCommon.sharedAppSettings,
      BuildCommon.cantonWarts,
      bundleTask,
      assembly / test := {}, // don't run tests during assembly
      // when building the fat jar, we need to properly merge our artefacts
      assembly / assemblyMergeStrategy := mergeStrategy((assembly / assemblyMergeStrategy).value),
      assembly / mainClass := Some("com.daml.network.CoinApp"),
      assembly / assemblyJarName := s"coin-${version.value}.jar",
    )

// https://tanin.nanakorn.com/technical/2018/09/10/parallelise-tests-in-sbt-on-circle-ci.html
// also used by Canton team
lazy val printTests = taskKey[Unit](
  "write full class names of `apps-app` tests to `test-full-class-names.log`; used for CI test splitting"
)
printTests := {
  import java.io._
  println("Appending full class names of tests to the file `test-full-class-names.log`.")
  val pw = new PrintWriter(new FileWriter(s"test-full-class-names.log", true))
  val tests =
    definedTests
      .all(ScopeFilter(inAggregates(root), inConfigurations(Test)))
      .value
      .flatten
  println(s"There are ${tests.length} tests.")
  tests.sortBy(_.name).foreach { test =>
    pw.println(test.name)
  }
  pw.close()
}
