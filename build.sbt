import BuildUtil.runCommand
import Dependencies._
import DamlPlugin.autoImport._
import BuildCommon.defs._
import sbtassembly.{MergeStrategy, PathList}

/*
 * sbt-settings that will be shared between all CN apps.
 */

BuildCommon.sbtSettings

// sbt insists on these re-declarations
lazy val `canton-community-app` = BuildCommon.`canton-community-app`
lazy val `canton-community-app-base` = BuildCommon.`canton-community-app-base`
lazy val `canton-community-base` = BuildCommon.`canton-community-base`
lazy val `canton-community-common` = BuildCommon.`canton-community-common`
lazy val `canton-community-domain` = BuildCommon.`canton-community-domain`
lazy val `canton-community-participant` = BuildCommon.`canton-community-participant`
lazy val `canton-community-integration-testing` = BuildCommon.`canton-community-integration-testing`
lazy val `canton-community-testing` = BuildCommon.`canton-community-testing`
lazy val `canton-research-services` = BuildCommon.`canton-research-services`
lazy val `canton-blake2b` = BuildCommon.`canton-blake2b`
lazy val `canton-functionmeta` = BuildCommon.`canton-functionmeta`
lazy val `canton-slick-fork` = BuildCommon.`canton-slick-fork`
lazy val `canton-daml-fork` = BuildCommon.`canton-daml-fork`
lazy val `canton-wartremover-extension` = BuildCommon.`canton-wartremover-extension`
lazy val `canton-util-external` = BuildCommon.`canton-util-external`
lazy val `canton-util-internal` = BuildCommon.`canton-util-internal`
lazy val `canton-akka-fork` = BuildCommon.`canton-akka-fork`
lazy val `canton-ledger-common` = BuildCommon.`canton-ledger-common`
lazy val `canton-ledger-api-core` = BuildCommon.`canton-ledger-api-core`

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
    `apps-splitwell`,
    `apps-svc`,
    `apps-sv`,
    `apps-app`,
    `apps-wallet`,
    `apps-directory`,
    `apps-frontends`,
    `cn-util-daml`,
    `canton-coin-api-daml`,
    `canton-coin-daml`,
    `canton-coin-v1test-daml`,
    `wallet-payments-daml`,
    `wallet-daml`,
    `wallet-v1test-daml`,
    `directory-daml`,
    `splitwell-daml`,
    `svc-governance-daml`,
    `svc-governance-v1test-daml`,
    `validator-lifecycle-daml`,
    `canton-community-base`,
    `canton-community-common`,
    `canton-community-integration-testing`,
    `canton-community-testing`,
    `canton-research-services`,
    `canton-blake2b`,
    `canton-slick-fork`,
    `canton-functionmeta`,
    `canton-wartremover-extension`,
    `canton-community-app`,
    `canton-community-app-base`,
    `canton-community-domain`,
    `canton-community-participant`,
    `canton-ledger-common`,
    `canton-ledger-api-core`,
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
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value,
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

lazy val `canton-coin-v1test-daml` =
  project
    .in(file("daml/upgrade-tests/cc-upgrade-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value ++
          (`canton-coin-daml` / Compile / damlBuild).value,
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

lazy val `svc-governance-v1test-daml` =
  project
    .in(file("daml/upgrade-tests/svc-governance-upgrade-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`svc-governance-daml` / Compile / damlBuild).value ++
          (`cn-util-daml` / Compile / damlBuild).value ++
          (`canton-coin-daml` / Compile / damlBuild).value ++
          (`canton-coin-v1test-daml` / Compile / damlBuild).value ++
          (`canton-coin-api-daml` / Compile / damlBuild).value,
    )

lazy val `validator-lifecycle-daml` =
  project
    .in(file("daml/validator-lifecycle"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`cn-util-daml` / Compile / damlBuild).value,
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

lazy val `wallet-v1test-daml` =
  project
    .in(file("daml/upgrade-tests/wallet-upgrade-test"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`canton-coin-v1test-daml` / Compile / damlBuild).value ++
          (`canton-coin-daml` / Compile / damlBuild).value ++
          (`wallet-payments-daml` / Compile / damlBuild).value ++
          (`wallet-daml` / Compile / damlBuild).value,
    )

lazy val `directory-daml` =
  project
    .in(file("daml/directory-service"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`wallet-daml` / Compile / damlBuild).value,
    )

lazy val `splitwell-daml` =
  project
    .in(file("daml/splitwell"))
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
      `canton-community-testing` % "test",
      `canton-coin-daml`,
      `canton-coin-v1test-daml`,
      `canton-research-services`,
      `wallet-daml` % "test",
      `wallet-v1test-daml` % "test",
      `splitwell-daml` % "test",
    )
    .enablePlugins(BuildInfoPlugin)
    .settings(
      libraryDependencies ++= Seq(
        Dependencies.daml_lf_value_json,
        scalatestScalacheck % Test,
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
        ),
        BuildInfoKey(
          "commitUnixTimestamp",
          BuildUtil.runCommandOptionalLog(Seq("git", "show", "-s", "--format=%ct", "HEAD")),
        ),
      ),
      buildInfoPackage := "com.daml.network.environment",
      buildInfoObject := "BuildInfo",
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/common/src/main/openapi/common.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
          ScalaClient(
            new File("apps/common/src/main/openapi/common.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
        ),
    )

lazy val `apps-validator` =
  project
    .in(file("apps/validator"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `apps-sv` % "compile->compile;test->test",
      `wallet-daml`,
      `canton-coin-v1test-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(akka_http_cors),
      BuildCommon.sharedAppSettings,
      BuildCommon.TS.openApiSettings(
        npmName = "validator-openapi",
        openApiSpec = "validator.yaml",
      ),
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
    .dependsOn(`svc-governance-v1test-daml`)

lazy val `apps-sv` =
  project
    .in(file("apps/sv"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(akka_http_cors),
      BuildCommon.sharedAppSettings,
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/sv/src/main/openapi/sv.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
          ScalaClient(
            new File("apps/sv/src/main/openapi/sv.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
        ),
    )
    .dependsOn(
      `apps-svc`,
      `directory-daml`,
      `validator-lifecycle-daml`,
    )

lazy val `apps-scan` =
  project
    .in(file("apps/scan"))
    .dependsOn(`apps-common` % "compile->compile;test->test")
    .settings(
      libraryDependencies ++= Seq(akka_http_cors, scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      BuildCommon.TS.openApiSettings(
        npmName = "scan-openapi",
        openApiSpec = "scan.yaml",
      ),
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
      `apps-splitwell`,
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
          (`splitwell-daml` / Compile / damlBuild).value,
      damlTsCodegenDir := baseDirectory.value / "daml.js",
      damlTsCodegen := BuildCommon.damlTsCodegenTask.value,
      npmInstallDeps := baseDirectory.value / "package.json" +: damlTsCodegen.value,
      npmInstallOpenApiDeps :=
        Seq(
          (
            (`apps-validator` / Compile / compile).value,
            (`apps-validator` / Compile / baseDirectory).value,
          ),
          (
            (`apps-directory` / Compile / compile).value,
            (`apps-directory` / Compile / baseDirectory).value,
          ),
          (
            (`apps-wallet` / Compile / compile).value,
            (`apps-wallet` / Compile / baseDirectory).value,
          ),
        ),
      npmInstall := BuildCommon.npmInstallTask.value,
      npmRootDir := baseDirectory.value / "../..",
      Compile / compile := {
        npmInstall.value
        (Compile / compile).value
      },
      bundle := {
        (Compile / compile).value
        (`apps-common-frontend-protobuf` / Compile / compile).value
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
            BuildCommon.TS.runBuildCommand(
              npmRootDir.value,
              "scan/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runBuildCommand(
              npmRootDir.value,
              "directory/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runBuildCommand(
              npmRootDir.value,
              "validator/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runBuildCommand(
              npmRootDir.value,
              "wallet/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runBuildCommand(
              npmRootDir.value,
              "common-frontend",
              log,
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
          Seq("npm", "run", "check", "--workspaces", "--if-present"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmFix := {
        val log = streams.value.log
        runCommand(
          Seq("npm", "run", "fix", "--workspaces", "--if-present"),
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

lazy val `apps-wallet-frontend-new` = {
  project
    .in(file("apps/wallet/frontend-new"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "wallet-frontend-new",
      sharedFrontendSettings,
    )
}

lazy val `apps-scan-frontend` = {
  project
    .in(file("apps/scan/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "scan-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-splitwell-frontend` = {
  project
    .in(file("apps/splitwell/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "splitwell-frontend",
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

lazy val `apps-common-frontend-protobuf` = {
  project
    .in(file("apps/common/frontend-protobuf"))
    .dependsOn(
      `apps-common` % "compile->protocGenerate",
      `apps-directory` % "compile->protocGenerate",
      `apps-wallet` % "compile->protocGenerate",
      `apps-splitwell` % "compile->protocGenerate",
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
    `apps-common-frontend`,
    `apps-wallet-frontend`,
    `apps-wallet-frontend-new`,
    `apps-directory-frontend`,
    `apps-scan-frontend`,
    `apps-splitwell-frontend`,
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
      `canton-coin-v1test-daml`,
      // TODO (#2676) Remove this once we have multi-domain interface support.
      `directory-daml`,
      `splitwell-daml`,
    )
    .settings(
      BuildCommon.sharedAppSettings,
      BuildCommon.TS.openApiSettings(
        npmName = "wallet-openapi",
        openApiSpec = "wallet.yaml",
      ),
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/wallet/src/main/openapi/wallet.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
            customExtraction = true,
          ),
          ScalaClient(
            new File("apps/wallet/src/main/openapi/wallet.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "akka-http",
          ),
        ),
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
      libraryDependencies ++= Seq(akka_http_cors),
      BuildCommon.TS.openApiSettings(
        npmName = "directory-openapi",
        openApiSpec = "directory.yaml",
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

lazy val `apps-splitwell` =
  project
    .in(file("apps/splitwell"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-directory` % "compile->compile;test->test",
      `splitwell-daml`,
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
        ((`apps-wallet-frontend-new` / bundle).value, "wallet-new"),
        ((`apps-directory-frontend` / bundle).value, "directory"),
        ((`apps-scan-frontend` / bundle).value, "scan"),
        ((`apps-splitwell-frontend` / bundle).value, "splitwell"),
      )
    val dars =
      Seq(
        (`canton-coin-daml` / Compile / damlBuild).value,
        (`wallet-daml` / Compile / damlBuild).value,
        (`splitwell-daml` / Compile / damlBuild).value,
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

lazy val runShellcheck = taskKey[Unit]("Check shell scripts with shellcheck")
runShellcheck := {
  val log = streams.value.log
  runCommand(Seq("pre-commit", "run", "--all-files", "shellcheck"), log)
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

lazy val checkErrors = taskKey[Unit](
  "Check test log and canton logs for errors and fail if there is one (works best if Canton is no longer running)"
)
checkErrors := {
  import scala.sys.process._

  def ignorePatternsFilename(patternsName: String): String =
    s"project/ignore-patterns/$patternsName.ignore.txt"

  def checkLogs(logFileName: String, ignorePatterns: Seq[String]): Unit = {
    val ignorePatternsFilenames = ignorePatterns.map(ignorePatternsFilename)
    val cmd =
      Seq(
        ".circleci/canton-scripts/check-logs.sh",
        logFileName,
      ) ++ ignorePatternsFilenames
    if (cmd.! != 0) {
      sys.error(s"$logFileName contains problems.")
    }
  }

  def splitAndCheckCantonLogFile(logName: String, usesSimtime: Boolean): Unit = {
    val logFile = s"log/${logName}.clog"
    val logFileBefore = s"log/${logName}_before_shutdown.clog"
    val logFileAfter = s"log/${logName}_after_shutdown.clog"

    // Note that this will split the given file and then delete it, so it is idempotent.
    Seq(".circleci/canton-scripts/split-canton-logs.sh", logFile, logFileBefore, logFileAfter).!

    val simtimeIgnorePatterns = if (usesSimtime) Seq("canton_log_simtime_extra") else Seq.empty
    val beforeIgnorePatterns = Seq("canton_log") ++ simtimeIgnorePatterns
    val afterIgnorePatterns = beforeIgnorePatterns ++ Seq("canton_log_shutdown_extra")

    checkLogs(logFileBefore, beforeIgnorePatterns)
    checkLogs(logFileAfter, afterIgnorePatterns)
  }

  splitAndCheckCantonLogFile("canton", usesSimtime = false)
  splitAndCheckCantonLogFile("canton-simtime", usesSimtime = true)
  splitAndCheckCantonLogFile("canton-standalone", usesSimtime = false)
  checkLogs("log/canton_network_test.clog", Seq("canton_network_test_log"))
}

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    .dependsOn(
      `wallet-payments-daml`,
      `wallet-daml`,
      `wallet-v1test-daml`,
      `apps-splitwell`,
      `apps-directory`,
      `apps-validator`,
      `apps-svc`,
      `apps-sv`,
      `apps-scan`,
      `apps-wallet`,
      `canton-coin-api-daml`,
      `canton-community-app` % "compile->compile;test->test",
      `canton-community-base`,
      `canton-community-integration-testing` % "test",
    )
    .settings(
      libraryDependencies += "org.scalatestplus" %% "selenium-4-7" % "3.2.15.0" % "test",
      libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "4.8.0" % "test",
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
      assembly / mainClass := Some("com.daml.network.CNNodeApp"),
      assembly / assemblyJarName := s"cn-node-${version.value}.jar",
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
  val pwSimTime = new PrintWriter(new FileWriter(s"test-full-class-names-sim-time.log", true))

  def isTimeBasedTest(name: String): Boolean = name.contains("TimeBased")
  def isPreflightIntegrationTest(name: String): Boolean = name.contains("PreflightIntegrationTest")
  def printTestNames(
      testSet: String,
      testNames: Seq[String],
      writer: PrintWriter,
      predicate: String => Boolean,
  ): Unit = {
    println(s"There are ${testNames.length} $testSet.")
    testNames.filter(predicate).sorted.foreach { testName =>
      writer.println(testName)
    }
  }

  val allTestNames =
    definedTests
      .all(ScopeFilter(inAggregates(root), inConfigurations(Test)))
      .value
      .flatten
      .map(_.name)

  printTestNames(
    "tests with wall clock time",
    allTestNames,
    pw,
    t => !isTimeBasedTest(t) && !isPreflightIntegrationTest(t),
  )
  printTestNames(
    "tests with simulated time",
    allTestNames,
    pwSimTime,
    t => isTimeBasedTest(t) && !isPreflightIntegrationTest(t),
  )

  pw.close()
  pwSimTime.close()
}
