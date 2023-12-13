import BuildUtil.runCommand
import Dependencies.*
import DamlPlugin.autoImport.*
import BuildCommon.defs.*
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
lazy val `canton-blake2b` = BuildCommon.`canton-blake2b`
lazy val `canton-slick-fork` = BuildCommon.`canton-slick-fork`
lazy val `canton-wartremover-extension` = BuildCommon.`canton-wartremover-extension`
lazy val `canton-util-external` = BuildCommon.`canton-util-external`
lazy val `canton-util-internal` = BuildCommon.`canton-util-internal`
lazy val `canton-util-logging` = BuildCommon.`canton-util-logging`
lazy val `canton-pekko-fork` = BuildCommon.`canton-pekko-fork`
lazy val `canton-ledger-common` = BuildCommon.`canton-ledger-common`
lazy val `canton-ledger-api-core` = BuildCommon.`canton-ledger-api-core`
lazy val `canton-ledger-json-api` = BuildCommon.`canton-ledger-json-api`
lazy val `canton-daml-errors` = BuildCommon.`canton-daml-errors`

lazy val `cn-wartremover-extension` = Wartremover.`cn-wartremover-extension`

inThisBuild(
  List(
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
  )
)

val allDarsFilter = ScopeFilter(inAnyProject, inConfigurations(Compile), inTasks(damlBuild))

/*
 * Root project
 */
lazy val root = (project in file("."))
  .aggregate(
    `apps-common`,
    `apps-validator`,
    `apps-scan`,
    `apps-splitwell`,
    `apps-sv`,
    `apps-app`,
    `apps-wallet`,
    `apps-frontends`,
    `cn-util-daml`,
    `canton-coin-daml`,
    `canton-coin-upgrade-daml`,
    `canton-name-service-daml`,
    `canton-name-service-upgrade-daml`,
    `wallet-payments-daml`,
    `wallet-payments-upgrade-daml`,
    `wallet-daml`,
    `wallet-upgrade-daml`,
    `splitwell-daml`,
    `svc-governance-daml`,
    `svc-governance-upgrade-daml`,
    `sv-local-daml`,
    `validator-lifecycle-daml`,
    `app-manager-daml`,
    `build-tools-dar-lock-checker`,
    `canton-community-base`,
    `canton-community-common`,
    `canton-community-integration-testing`,
    `canton-community-testing`,
    `canton-blake2b`,
    `canton-slick-fork`,
    `canton-wartremover-extension`,
    `canton-community-app`,
    `canton-community-app-base`,
    `canton-community-domain`,
    `canton-community-participant`,
    `canton-ledger-common`,
    `canton-ledger-api-core`,
    pulumi,
    `load-tester`,
    tools,
    `cn-wartremover-extension`,
  )
  .settings(
    BuildCommon.sharedSettings,
    scalacOptions += "-Wconf:src=src_managed/.*:silent",
    // Needed to be able to resolve scalafmt snapshot versions
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    damlDarsLockCheckerFileArg := {
      val darFiles: Seq[File] = damlBuild.all(allDarsFilter).value.flatten
      val basePath = baseDirectory.value.toPath
      val cantonPath = basePath.resolve("canton")
      val darPaths = for {
        file <- darFiles
        path = file.toPath
        if !path.startsWith(cantonPath)
      } yield basePath.relativize(path)
      val outputFile = "daml/dars.lock"
      " " + (Seq(outputFile) ++ darPaths).mkString(" ")
    },
    cantonDarsLockCheckerFileArg := {
      val darFiles: Seq[File] = damlBuild.all(allDarsFilter).value.flatten
      val basePath = baseDirectory.value.toPath
      val cantonPath = basePath.resolve("canton")
      val darPaths = for {
        file <- darFiles
        path = file.toPath
        if path.startsWith(cantonPath)
      } yield basePath.relativize(path)
      val outputFile = "canton/dars.lock"
      " " + (Seq(outputFile) ++ darPaths).mkString(" ")
    },
    damlDarsLockFileUpdate :=
      Def.taskDyn {
        (`build-tools-dar-lock-checker` / Compile / run)
          .toTask(" update" + damlDarsLockCheckerFileArg.value)
      }.value,
    cantonDarsLockFileUpdate :=
      Def.taskDyn {
        (`build-tools-dar-lock-checker` / Compile / run)
          .toTask(" update" + cantonDarsLockCheckerFileArg.value)
      }.value,
    damlDarsLockFileCheck :=
      Def.taskDyn {
        (`build-tools-dar-lock-checker` / Compile / run)
          .toTask(" check" + damlDarsLockCheckerFileArg.value)
      }.value,
    cantonDarsLockFileCheck :=
      Def.taskDyn {
        (`build-tools-dar-lock-checker` / Compile / run)
          .toTask(" check" + cantonDarsLockCheckerFileArg.value)
      }.value,
  )

val damlDarsLockFileCheck = taskKey[Unit]("Check the daml/dars.lock file")
val damlDarsLockFileUpdate = taskKey[Unit]("Update the daml/dars.lock file")
val damlDarsLockCheckerFileArg =
  taskKey[String]("Argument line for updating the daml/dars.lock file")
val cantonDarsLockFileCheck = taskKey[Unit]("Check the canton/dars.lock file")
val cantonDarsLockFileUpdate = taskKey[Unit]("Update the canton/dars.lock file")
val cantonDarsLockCheckerFileArg =
  taskKey[String]("Argument line for updating the canton/dars.lock file")

lazy val `build-tools-dar-lock-checker` = project
  .in(file("build-tools/dar-lock-checker"))
  .settings(
    libraryDependencies ++= Seq(Dependencies.better_files, Dependencies.daml_lf_archive_reader)
  )

lazy val `tools` = project
  .in(file("apps/tools"))
  .dependsOn(`apps-app` % "compile->test")
  .settings(
    libraryDependencies += auth0
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

lazy val `canton-coin-daml` =
  project
    .in(file("daml/canton-coin"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value,
      // We generate code for the latest version
      Compile / damlEnableJavaCodegen := false,
    )

lazy val `canton-coin-upgrade-daml` =
  project
    .in(file("daml/canton-coin-upgrade"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value,
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
          (`canton-name-service-daml` / Compile / damlBuild).value ++
          (`wallet-payments-daml` / Compile / damlBuild).value,
      // We generate code for the latest version
      Compile / damlEnableJavaCodegen := false,
    )

lazy val `svc-governance-upgrade-daml` =
  project
    .in(file("daml/svc-governance-upgrade"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value ++
          (`canton-coin-upgrade-daml` / Compile / damlBuild).value ++
          (`canton-name-service-upgrade-daml` / Compile / damlBuild).value ++
          (`wallet-payments-upgrade-daml` / Compile / damlBuild).value,
    )

lazy val `sv-local-daml` =
  project
    .in(file("daml/sv-local"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings
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
          (`canton-coin-daml` / Compile / damlBuild).value,
      // We generate code for the latest version
      Compile / damlEnableJavaCodegen := false,
    )

lazy val `wallet-payments-upgrade-daml` =
  project
    .in(file("daml/wallet-payments-upgrade"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies :=
        (`cn-util-daml` / Compile / damlBuild).value ++
          (`canton-coin-upgrade-daml` / Compile / damlBuild).value,
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
      // We generate code for the latest version
      Compile / damlEnableJavaCodegen := false,
    )

lazy val `wallet-upgrade-daml` =
  project
    .in(file("daml/wallet-upgrade"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`canton-coin-upgrade-daml` / Compile / damlBuild).value ++ (`wallet-payments-upgrade-daml` / Compile / damlBuild).value,
    )

lazy val `canton-name-service-daml` =
  project
    .in(file("daml/canton-name-service"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`wallet-daml` / Compile / damlBuild).value,
      // We generate code for the latest version
      Compile / damlEnableJavaCodegen := false,
    )

lazy val `canton-name-service-upgrade-daml` =
  project
    .in(file("daml/canton-name-service-upgrade"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`wallet-upgrade-daml` / Compile / damlBuild).value,
    )

lazy val `splitwell-daml` =
  project
    .in(file("daml/splitwell"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`wallet-daml` / Compile / damlBuild).value,
      // We generate code for the latest version
      Compile / damlEnableJavaCodegen := false,
    )

lazy val `splitwell-upgrade-daml` =
  project
    .in(file("daml/splitwell-upgrade"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings,
      Compile / damlDependencies := (`wallet-upgrade-daml` / Compile / damlBuild).value,
    )

lazy val `app-manager-daml` =
  project
    .in(file("daml/app-manager"))
    .enablePlugins(DamlPlugin)
    .settings(
      BuildCommon.damlSettings
    )

lazy val `apps-common` =
  project
    .in(file("apps/common"))
    .dependsOn(
      `canton-community-common`,
      `canton-community-app` % "compile->compile;test->test",
      `canton-community-testing` % "test",
      `cn-wartremover-extension` % "compile->compile;test->test",
      // We include all DARs here to make sure they are available as resources.
      `app-manager-daml`,
      `app-manager-daml`,
      `canton-coin-daml`,
      `canton-coin-upgrade-daml`,
      `canton-name-service-daml`,
      `canton-name-service-upgrade-daml`,
      `splitwell-daml`,
      `splitwell-upgrade-daml`,
      `sv-local-daml`,
      `svc-governance-daml`,
      `svc-governance-upgrade-daml`,
      `validator-lifecycle-daml`,
      `wallet-daml`,
      `wallet-upgrade-daml`,
      `wallet-payments-daml`,
      `wallet-payments-upgrade-daml`,
    )
    .enablePlugins(BuildInfoPlugin)
    .settings(
      libraryDependencies ++= Seq(
        google_cloud_storage,
        kubernetes_client,
        Dependencies.daml_lf_validation,
        scalatestScalacheck % Test,
        scalapb_runtime_grpc,
        scalapb_runtime,
        daml_ledger_api_scalapb,
        daml_ledger_api_proto % "protobuf",
        java_jwt,
        jwks_rsa,
        spray_json,
        pekko_spray_json,
      ),
      BuildCommon.sharedAppSettings,
      buildInfoKeys := Seq[BuildInfoKey](
        BuildInfoKey(
          "compiledVersion",
          BuildUtil.runCommandOptionalLog(Seq("./build-tools/get-snapshot-version")),
        ),
        BuildInfoKey(
          "commitUnixTimestamp",
          BuildUtil.runCommandOptionalLog(Seq("git", "show", "-s", "--format=%ct", "HEAD")),
        ),
      ),
      buildInfoPackage := "com.daml.network.environment",
      buildInfoObject := "BuildInfo",
      Compile / guardrailTasks :=
        List("external", "internal").flatMap { scope =>
          List(
            ScalaServer(
              new File(s"apps/common/src/main/openapi/common-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
              customExtraction = true,
            ),
            ScalaClient(
              new File(s"apps/common/src/main/openapi/common-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
            ),
          )
        },
    )

lazy val `apps-validator` =
  project
    .in(file("apps/validator"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `apps-sv` % "compile->compile;test->test",
      `wallet-daml`,
      `apps-wallet`,
      `app-manager-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(pekko_http_cors, commons_compress, jaxb_abi),
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "validator-openapi",
        openApiSpec = "validator-internal.yaml",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "jsonapi-proxy-openapi",
        openApiSpec = "json-api-proxy-internal.yaml",
        directory = "jsonapi-proxy-ts-client",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "cns-external-openapi",
        openApiSpec = "cns-external.yaml",
        directory = "external-openapi-ts-client",
      ),
      Compile / guardrailTasks :=
        List("validator-internal", "json-api-proxy-internal", "cns-external").flatMap(api =>
          List(
            ScalaServer(
              new File(s"apps/validator/src/main/openapi/${api}.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
              customExtraction = true,
            ),
            ScalaClient(
              new File(s"apps/validator/src/main/openapi/${api}.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
            ),
          )
        ),
    )

lazy val `apps-sv` =
  project
    .in(file("apps/sv"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan`,
      `validator-lifecycle-daml`,
      `svc-governance-daml`,
      `sv-local-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(
        pekko_http_cors,
        scalapb_runtime,
        comet_bft_proto,
      ),
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "sv-openapi",
        openApiSpec = "sv-internal.yaml",
      ),
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/sv/src/main/openapi/sv-internal.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "pekko-http",
            customExtraction = true,
          ),
          ScalaClient(
            new File("apps/sv/src/main/openapi/sv-internal.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "pekko-http",
          ),
        ),
    )

lazy val `apps-scan` =
  project
    .in(file("apps/scan"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `svc-governance-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(pekko_http_cors, scalapb_runtime_grpc, scalapb_runtime),
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "scan-external-openapi",
        openApiSpec = "scan-external.yaml",
        directory = "external-openapi-ts-client",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "scan-openapi",
        openApiSpec = "scan-internal.yaml",
      ),
      Compile / guardrailTasks :=
        List("external", "internal").flatMap { scope =>
          List(
            ScalaServer(
              new File(s"apps/scan/src/main/openapi/scan-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
              customExtraction = true,
            ),
            ScalaClient(
              new File(s"apps/scan/src/main/openapi/scan-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
            ),
          ),
        },
    )

lazy val `apps-common-frontend` = {
  project
    .in(file("apps/common/frontend"))
    .dependsOn(
      `apps-common`,
      `apps-wallet`,
      `apps-splitwell`,
      `apps-validator`,
    )
    .settings(
      // daml typescript code generation settings:
      damlTsCodegenSources :=
        (`canton-coin-upgrade-daml` / Compile / damlBuild).value ++
          (`wallet-upgrade-daml` / Compile / damlBuild).value ++
          (`wallet-payments-upgrade-daml` / Compile / damlBuild).value ++
          (`canton-name-service-upgrade-daml` / Compile / damlBuild).value ++
          (`svc-governance-upgrade-daml` / Compile / damlBuild).value ++
          (`splitwell-upgrade-daml` / Compile / damlBuild).value ++
          (`validator-lifecycle-daml` / Compile / damlBuild).value ++
          // Generated for package id resolution only
          (`splitwell-daml` / Compile / damlBuild).value ++
          (`wallet-payments-daml` / Compile / damlBuild).value,
      damlTsCodegenDir := baseDirectory.value / "daml.js",
      damlTsCodegen := BuildCommon.damlTsCodegenTask.value,
      npmInstallDeps := baseDirectory.value / "package.json" +: damlTsCodegen.value,
      npmInstallOpenApiDeps :=
        Seq(
          (
            (`apps-validator` / Compile / compile).value,
            (`apps-validator` / Compile / baseDirectory).value,
            false,
          ),
          (
            (`apps-sv` / Compile / compile).value,
            (`apps-sv` / Compile / baseDirectory).value,
            false,
          ),
          (
            (`apps-wallet` / Compile / compile).value,
            (`apps-wallet` / Compile / baseDirectory).value,
            true,
          ),
          (
            (`apps-splitwell` / Compile / compile).value,
            (`apps-splitwell` / Compile / baseDirectory).value,
            false,
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
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "scan/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "sv/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "validator/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "validator/jsonapi-proxy-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "validator/external-openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "wallet/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "wallet/external-openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "splitwell/openapi-ts-client",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "common-frontend",
              log,
            )
            BuildCommon.TS.runWorkspaceCommand(
              npmRootDir.value,
              "build",
              "common-test-utils",
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
      // TODO(#7579) -- like npmLint and npmFix above, we could/should run vitest per project.
      // In this case, we really want to do that asap to better parallelize the task in CI.
      npmTest := {
        val log = streams.value.log
        (Test / compile).value
        npmInstall.value
        BuildCommon.TS.runWorkspaceCommand(npmRootDir.value, "build", "common-frontend", log)
        BuildCommon.TS.runWorkspaceCommand(npmRootDir.value, "build", "common-test-utils", log)
        runCommand(
          Seq("npm", "run", "test:sbt", "--workspaces", "--if-present"),
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

lazy val `apps-cns-frontend` = {
  project
    .in(file("apps/cns/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "cns-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-sv-frontend` = {
  project
    .in(file("apps/sv/frontend"))
    .dependsOn(`apps-common-frontend`)
    .settings(
      commonFrontendBundle := (`apps-common-frontend` / bundle).value._2,
      frontendWorkspace := "sv-frontend",
      sharedFrontendSettings,
    )
}

lazy val `apps-frontends` = {
  project.aggregate(
    `apps-common-frontend`,
    `apps-wallet-frontend`,
    `apps-cns-frontend`,
    `apps-sv-frontend`,
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
      `wallet-daml`,
      `svc-governance-daml`,
    )
    .settings(
      BuildCommon.sharedAppSettings,
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "wallet-external-openapi",
        openApiSpec = "wallet-external.yaml",
        directory = "external-openapi-ts-client",
      ),
      BuildCommon.TS.openApiSettings(
        npmName = "wallet-openapi",
        openApiSpec = "wallet-internal.yaml",
      ),
      Compile / guardrailTasks :=
        List("external", "internal").flatMap { scope =>
          List(
            ScalaServer(
              new File(s"apps/wallet/src/main/openapi/wallet-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
              customExtraction = true,
            ),
            ScalaClient(
              new File(s"apps/wallet/src/main/openapi/wallet-$scope.yaml"),
              pkg = "com.daml.network.http.v0",
              framework = "pekko-http",
            ),
          )
        },
    )

lazy val `apps-splitwell` =
  project
    .in(file("apps/splitwell"))
    .dependsOn(
      `apps-common` % "compile->compile;test->test",
      `apps-scan` % "compile->compile;test->test",
      `splitwell-daml`,
    )
    .settings(
      libraryDependencies ++= Seq(scalapb_runtime_grpc, scalapb_runtime),
      templateDirectory := (`openapi-typescript-template` / patchTemplate).value,
      BuildCommon.TS.openApiSettings(
        npmName = "splitwell-openapi",
        openApiSpec = "splitwell-internal.yaml",
      ),
      BuildCommon.sharedAppSettings,
      Compile / guardrailTasks :=
        List(
          ScalaServer(
            new File("apps/splitwell/src/main/openapi/splitwell-internal.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "pekko-http",
            customExtraction = true,
          ),
          ScalaClient(
            new File("apps/splitwell/src/main/openapi/splitwell-internal.yaml"),
            pkg = "com.daml.network.http.v0",
            framework = "pekko-http",
          ),
        ),
      Compile / resourceGenerators += Def.task {
        val log = streams.value.log
        val splitwellOutput = (`splitwell-daml` / Compile / damlBuild).value
        val splitwellDar = ((`splitwell-daml` / Compile / damlBuild).value).head.toString
        val output1_0 =
          baseDirectory.value / "src" / "test" / "resources" / "splitwell-bundle-1.0.0.tar.gz"
        val output2_0 =
          baseDirectory.value / "src" / "test" / "resources" / "splitwell-bundle-2.0.0.tar.gz"
        val createBundle = baseDirectory.value / "../../scripts/create-bundle.sh"
        val cacheDir = streams.value.cacheDirectory
        val cache = FileFunction.cached(cacheDir) { _ =>
          runCommand(
            Seq(createBundle.toString, splitwellDar, "splitwell", "1.0.0", output1_0.toString),
            log,
            None,
            None,
          )
          runCommand(
            Seq(createBundle.toString, splitwellDar, "splitwell", "2.0.0", output2_0.toString),
            log,
            None,
            None,
          )
          Set(output1_0, output2_0)

        }
        cache((createBundle +: splitwellOutput).toSet).toSeq
      }.taskValue,
    )

lazy val pulumi =
  project
    .in(file("cluster/pulumi"))
    .disablePlugins(sbt.plugins.JvmPlugin, sbt.plugins.IvyPlugin)
    .settings(
      npmRootDir := baseDirectory.value,
      npmFix := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "fix"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmLint := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "check"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmInstall := {
        val s = streams.value
        val log = s.log
        val cacheDir = s.cacheDirectory
        val buildDir = (ThisBuild / baseDirectory).value
        val npmInstall = buildDir / "build-tools" / "npm-install.sh"
        val cache = FileFunction.cached(cacheDir / "npmInstall", FileInfo.hash) { _ =>
          runCommand(Seq(npmInstall.absolutePath), log, None, Some(npmRootDir.value))
          Set(npmRootDir.value / "node_modules")
        }
        cache(Set(npmRootDir.value / "package.json")).toSeq
      },
    )

lazy val `load-tester` =
  project
    .in(file("load-tester"))
    .disablePlugins(sbt.plugins.JvmPlugin, sbt.plugins.IvyPlugin)
    .settings(
      npmRootDir := baseDirectory.value,
      npmFix := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "fix"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmLint := {
        val log = streams.value.log
        npmInstall.value
        runCommand(
          Seq("npm", "run", "check"),
          log,
          None,
          Some(npmRootDir.value),
        )
      },
      npmInstall := {
        val s = streams.value
        val log = s.log
        val cacheDir = s.cacheDirectory
        val buildDir = (ThisBuild / baseDirectory).value
        val npmInstall = buildDir / "build-tools" / "npm-install.sh"
        val cache = FileFunction.cached(cacheDir / "npmInstall", FileInfo.hash) { _ =>
          runCommand(Seq(npmInstall.absolutePath), log, None, Some(npmRootDir.value))
          Set(npmRootDir.value / "node_modules")
        }
        cache(Set(npmRootDir.value / "package.json")).toSeq
      },
    )

lazy val patchTemplate = taskKey[File]("patch an openapi codegen template")

lazy val `openapi-typescript-template` =
  project
    .in(file("openapi-templates"))
    .settings(
      patchTemplate := {
        val log = streams.value.log
        val template = baseDirectory.value / "typescript"
        val patch = baseDirectory.value / "typescript.patch"
        // ensure directory exists
        runCommand(Seq("mkdir", "-p", s"$template"), log)
        // copy the typescript template out to the directory
        runCommand(
          Seq(
            "openapi-generator-cli",
            "author",
            "template",
            "-g",
            "typescript",
            "-o",
            s"$template",
          ),
          log,
        )
        // apply a patch file
        runCommand(Seq("patch", "-p0", "-i", s"$patch"), log, optCwd = Some(baseDirectory.value))
        template
      },
      cleanFiles += baseDirectory.value / "typescript",
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
    case PathList("com", "digitalasset", "canton", "config", "LocalNodeParametersConfig.class") =>
      MergeStrategy.first
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
    case (PathList("org", "apache", "pekko", "stream", "scaladsl", broadcasthub, _*))
        if broadcasthub.startsWith("BroadcastHub") =>
      MergeStrategy.first
    case "META-INF/versions/9/module-info.class" => MergeStrategy.discard
    case path if path.contains("module-info.class") => MergeStrategy.discard
    case PathList("org", "jline", _ @_*) => MergeStrategy.first
    // Dedup between ledger-api-java-proto (pulled in via Scala bindings)
    // and the copy of that inlined into bindings-java.
    case PathList("com", "daml", "ledger", "api", "v1" | "v2", _*) => MergeStrategy.first
    // Hack for not getting trouble with different versions of generated classes of common openapi
    case PathList("com", "daml", "network", "http", "v0" | "commonAdmin", _*) => MergeStrategy.first
    case x => oldStrategy(x)
  }
}

import sbtassembly.AssemblyPlugin.autoImport.assembly

/** Generate a release bundle. Simplified versions of Canton's release bundling (see Canton's code base / issue #147) */
lazy val bundleTask = {
  bundle := {
    val license = Seq("-c", "LICENSE.txt")
    val log = streams.value.log
    val assemblyJar = assembly.value
    val examples = Seq("-c", "apps/app/src/pack")
    val webUis =
      Seq(
        ((`apps-wallet-frontend` / bundle).value, "wallet"),
        ((`apps-cns-frontend` / bundle).value, "cns"),
        ((`apps-sv-frontend` / bundle).value, "sv"),
        ((`apps-scan-frontend` / bundle).value, "scan"),
        ((`apps-splitwell-frontend` / bundle).value, "splitwell"),
      )
    val dars =
      Seq(
        (`canton-coin-daml` / Compile / damlBuild).value,
        (`wallet-daml` / Compile / damlBuild).value,
        (`splitwell-daml` / Compile / damlBuild).value,
      )
    val args: Seq[String] = license ++ examples ++ webUis.flatMap({ case ((source, _), name) =>
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

lazy val runCheckUpgradeModelDiffs =
  taskKey[Unit]("Check that diffs for upgrade models are up2date")
runCheckUpgradeModelDiffs := {
  val log = streams.value.log
  runCommand(Seq("pre-commit", "run", "check-upgrade-model-diffs"), log)
}

lazy val syncpackCheck = taskKey[Unit]("Check all apps' package.json dependency versions match")
syncpackCheck := {
  val log = streams.value.log
  runCommand(Seq("syncpack", "list-mismatches"), log, None, Some(baseDirectory.value / "apps"))
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
  import better.files._
  val dir = File("log/")
  if (dir.exists())
    dir
      .glob("canton-standalone-*.clog")
      .map(_.nameWithoutExtension)
      .foreach(
        splitAndCheckCantonLogFile(_, usesSimtime = false)
      )

  checkLogs("log/canton_network_test.clog", Seq("canton_network_test_log"))
}

lazy val `apps-app` =
  project
    .in(file("apps/app"))
    .dependsOn(
      `wallet-payments-daml`,
      `wallet-daml`,
      `apps-splitwell`,
      `apps-validator`,
      `apps-sv` % "compile->compile;test->test",
      `apps-scan`,
      `apps-wallet`,
      `canton-community-app` % "compile->compile;test->test",
      `canton-community-base`,
      `canton-community-integration-testing` % "test",
    )
    .settings(
      libraryDependencies += "org.scalatestplus" %% "selenium-4-12" % "3.2.17.0" % "test",
      libraryDependencies += "org.seleniumhq.selenium" % "selenium-java" % "4.12.1" % "test",
      libraryDependencies += "eu.rekawek.toxiproxy" % "toxiproxy-java" % "2.1.4" % "test",
      libraryDependencies += google_cloud_storage,
      libraryDependencies += auth0,
      libraryDependencies += kubernetes_client,
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
  "write full class names of `apps-app` tests to separted files depending on whether the test is for Wall clock time vs Simulated time, Backend vs frontend, preflight; used for CI test splitting"
)
printTests := {
  import java.io._
  println("Appending full class names of tests.")

  def isTimeBasedTest(name: String): Boolean = name.contains("TimeBased")
  def isFrontEndTest(name: String): Boolean = name.contains("Frontend")
  def isNonDevNetTest(name: String): Boolean = name.contains("NonDevNet")
  def isPreflightIntegrationTest(name: String): Boolean =
    name.contains("PreflightIntegrationTest") && !isNonDevNetTest(name)
  def isNonDevNetPreflightIntegrationTest(name: String): Boolean =
    name.contains("PreflightIntegrationTest") && isNonDevNetTest(name)
  def isNonDevNetPreflightSvIntegrationTest(name: String): Boolean =
    name.contains("PreflightSvNonDevNetIntegrationTest") && isNonDevNetTest(name)
  def isPreflightSvIntegrationTest(name: String): Boolean =
    name.contains("PreflightSvIntegrationTest") && !isNonDevNetTest(name)
  def isPreflightValidatorIntegrationTest(name: String): Boolean =
    name.contains("PreflightValidatorIntegrationTest") && !isNonDevNetTest(name)
  def isNonDevNetPreflightValidatorIntegrationTest(name: String): Boolean =
    name.contains("PreflightValidatorIntegrationTest") && isNonDevNetTest(name)
  def isGlobalUpgradeTest(name: String): Boolean = name contains "GlobalDomainUpgrade"
  def isAppManagerTest(name: String): Boolean = name contains "AppManager"

  val allTestNames =
    definedTests
      .all(ScopeFilter(inAggregates(root), inConfigurations(Test)))
      .value
      .flatten
      .map(_.name)

  // Order matters as each test is included in just one group, with the first match being used
  val testSplitRules = Seq(
    (
      "Preflight tests",
      "test-full-class-names-preflight.log",
      (t: String) => isPreflightIntegrationTest(t),
    ),
    (
      "Non DevNet Preflight tests",
      "test-full-class-names-preflight-non-devnet.log",
      (t: String) => isNonDevNetPreflightIntegrationTest(t),
    ),
    // This file is only used in sbt printTests for checking the pattern matching, but not used in ci
    (
      "Non DevNet Preflight SV tests",
      "test-full-class-names-preflight-sv-non-devnet.log",
      (t: String) => isNonDevNetPreflightSvIntegrationTest(t),
    ),
    (
      "Non DevNet Preflight Validator tests",
      "test-full-class-names-preflight-validator-non-devnet.log",
      (t: String) => isNonDevNetPreflightValidatorIntegrationTest(t),
    ),
    // This file is only used in sbt printTests for checking the pattern matching, but not used in ci
    (
      "Preflight SV tests",
      "test-full-class-names-preflight-sv.log",
      (t: String) => isPreflightSvIntegrationTest(t),
    ),
    // This file is only used in sbt printTests for checking the pattern matching, but not used in ci
    (
      "Preflight Validator tests",
      "test-full-class-names-preflight-validator.log",
      (t: String) => isPreflightValidatorIntegrationTest(t),
    ),
    (
      "global domain upgrade test",
      "test-full-class-names-global-upgrade-sim-time.log",
      (t: String) => isTimeBasedTest(t) && isGlobalUpgradeTest(t),
    ),
    (
      "tests with wall clock time",
      "test-full-class-names.log",
      (t: String) => !isTimeBasedTest(t) && !isFrontEndTest(t),
    ),
    (
      "tests with simulated time",
      "test-full-class-names-sim-time.log",
      (t: String) => isTimeBasedTest(t) && !isFrontEndTest(t),
    ),
    (
      "frontend tests with wall clock time",
      "test-full-class-names-frontend.log",
      (t: String) => !isTimeBasedTest(t) && isFrontEndTest(t) && !isAppManagerTest(t),
    ),
    (
      "frontend tests with app manager",
      "test-full-class-names-frontend-app-manager.log",
      (t: String) => !isTimeBasedTest(t) && isFrontEndTest(t) && isAppManagerTest(t),
    ),
    (
      "frontend tests with simulated time",
      "test-full-class-names-frontend-sim-time.log",
      (t: String) => isTimeBasedTest(t) && isFrontEndTest(t),
    ),
  )

  val rulesWithOpenFiles = testSplitRules.map { case (t, fileName, p) =>
    // append writes from the start of the file
    // if any content already exists and is longer than the content we're writing
    // it will produce a garbled mess between the two sources
    // therefore we first ensure that the file is deleted
    new File(fileName).delete()
    (t, new PrintWriter(new FileWriter(fileName, false)), p)
  }.zipWithIndex

  val (testCounts, unmatchedTestNames) =
    allTestNames.sorted.foldLeft((Map.empty[Int, Int], Vector.empty[String])) {
      case ((counts, unmatched), testName) =>
        rulesWithOpenFiles
          .collectFirst {
            case ((testSet, writer, predicate), countIx) if predicate(testName) =>
              (writer, countIx)
          }
          .map { case (writer, countIx) =>
            writer.println(testName)
            (counts.updated(countIx, counts.getOrElse(countIx, 0) + 1), unmatched)
          }
          .getOrElse {
            (counts, unmatched :+ testName)
          }
    }

  if (unmatchedTestNames.nonEmpty)
    sys.error(
      s"Could not find a matching CI category for tests ${unmatchedTestNames mkString ", "}"
    )

  rulesWithOpenFiles.foreach { case ((testSet, writer, _), countIx) =>
    val filteredLength = testCounts.getOrElse(countIx, 0)
    println(s"There are $filteredLength $testSet.")
    writer.close()
  }
}

Global / excludeLintKeys += `root` / wartremoverErrors
