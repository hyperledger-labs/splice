// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import BuildUtil.runCommand
import scalafix.sbt.ScalafixPlugin
import sbt.Keys.*
import sbt.*
import Dependencies.*
import org.scalafmt.sbt.ScalafmtPlugin
import sbt.nio.Keys.*
import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport.*
import wartremover.WartRemover
import wartremover.WartRemover.autoImport.*
import sbtprotoc.ProtocPlugin.autoImport.PB
import DamlPlugin.autoImport.*
import Wartremover.spliceWarts
import protocbridge.ProtocRunner
import sbt.internal.util.ManagedLogger
import xsbti.compile.CompileAnalysis
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{headerResources, headerSources}
import CantonDependencies.daml_ledger_api_value_proto

import java.util.concurrent.atomic.AtomicInteger

object BuildCommon {

  object defs {
    lazy val bundle = taskKey[(File, Set[File])]("create a release bundle")
    lazy val damlTsCodegen = taskKey[Seq[File]]("generate typescript for the daml models")
    lazy val damlTsCodegenDir =
      settingKey[File]("directory for auto-generated typescript for the daml models")
    lazy val damlTsCodegenSources = taskKey[Seq[File]]("dars to generate ts code from")

    lazy val npmInstallDeps = taskKey[Seq[File]]("Dependencies for npm install task")
    type HasExternalOpenAPISpec = Boolean
    lazy val npmInstallOpenApiDeps =
      taskKey[Seq[(CompileAnalysis, File, HasExternalOpenAPISpec)]](
        "Dependencies for npm install task"
      )
    lazy val npmRootDir = settingKey[File]("npm workspaces root directory")
    lazy val npmInstall = taskKey[Seq[File]]("install npm dependencies")
    lazy val npmLint =
      taskKey[Unit]("checks formatting of frontend code, but does not fix anything")
    lazy val npmFix = taskKey[Unit]("fixes formatting of frontend code")
    lazy val npmTest = taskKey[Unit]("run all frontend unit tests")
    lazy val npmGenerateViteReport = taskKey[Unit]("generate html vite test reports")
    lazy val npmBuild = taskKey[Unit]("build an npm project")

    lazy val compileOpenApi = taskKey[Seq[File]]("build typescript code")
    lazy val templateDirectory = taskKey[File]("directory to openapi template")
    lazy val frontendWorkspace = settingKey[String]("npm workspace to bundle")
    lazy val commonFrontendBundle =
      taskKey[Set[File]]("common frontend bundle task to run before the app frontend bundle")
  }

  lazy val sharedProtocSettings: Seq[Def.Setting[_]] = Seq(
    // Change to use protoc from nix to avoid weird linking issues where otherwise
    // we use protoc downloaded from maven but protoc-bridge tries to be clever
    // and uses the nix linker.
    Compile / PB.protocExecutable := new java.io.File(s"${sys.env("PROTOC")}/bin/protoc")
  )

  lazy val sharedSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies ++= Seq(
      scalatest % Test
    )
  ) ++ sharedProtocSettings ++ Headers.NoHeaderSettings

  val pbTsDirectory = SettingKey[File]("output directory for ts protobuf definitions")

  lazy val sharedAppSettings: Seq[Def.Setting[_]] =
    sharedSettings ++ cantonWarts ++ spliceWarts ++ unusedImportsSetting ++ Headers.ApacheDAHeaderSettings ++
      Seq(
        Compile / PB.deleteTargetDirectory := false,
        // ^^ do not let protocGenerate delete the entire target directory, otherwise the different apps
        // are deleting each other's outputs. The downside is that for a file name change, or removing a file -
        // we will need to manually clean it first.
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
        ),
        Compile / PB.protoSources ++= (Test / PB.protoSources).value,
        scalacOptions ++= Seq(
          "-Wconf:src=src_managed/.*:silent",
          // Silencing deprecation warnings for now for javaapi, remove when on canton-3.4
          "-Wconf:msg=.*package command_service.*|.*CommandService.*|.*package update_service.*|.*UpdateService.*|.*transaction_filter.*|.*package data.*|.*package transaction.*&cat=deprecation:silent",
          // renable once scala is > 2.13.15 https://github.com/scala/bug/issues/13041
          // "-Wunused:patvars",
          "-Wunused:privates",
          "-Wunused:params",
          // https://github.com/scala/bug/issues/12883 I have no idea what's the purpouse of that warning
          "-Wconf:msg=access modifiers for `.*` method are copied from the case class constructor under Scala 3:s",
          "-quickfix:any",
        ),
        Test / testOptions ++= Seq(
          // Enable logging of begin and end of test cases, test suites, and test runs.
          Tests.Argument("-C", "com.digitalasset.canton.LogReporter")
        ),
      )

  lazy val damlSettings: Seq[Def.Setting[_]] =
    BuildCommon.sharedAppSettings ++
      BuildCommon.copyDarResources ++
      Seq(
        Compile / damlCodeGeneration := {
          val Seq(darFile, _) = (Compile / damlBuild).value
          Seq(
            (
              (Compile / baseDirectory).value,
              darFile,
              "org.lfdecentralizedtrust.splice.codegen",
            )
          )
        }
      )

  lazy val copyDarResources: Seq[Def.Setting[_]] = {
    Seq(
      Compile / resourceGenerators += Def.task {
        val Seq(versionedDar, currentDar) = (Compile / damlBuild).value
        val darName = currentDar.getName.stripSuffix("-current.dar")
        val dars =
          java.nio.file.Paths.get("daml").resolve("dars").toFile.listFiles(s"$darName-*.dar").toSeq
        val dstVersionedFile = (Compile / resourceDirectory).value / "dar" / versionedDar.getName()
        IO.copyFile(versionedDar, dstVersionedFile)
        val dstCurrentFile = (Compile / resourceDirectory).value / "dar" / currentDar.getName()
        IO.copyFile(currentDar, dstCurrentFile)

        val targets = dars.filter(_.getName != versionedDar.getName).map { dar =>
          val target = (Compile / resourceDirectory).value / dar.getName
          IO.copyFile(dar, target)
          target
        }

        Seq(dstVersionedFile, dstCurrentFile) ++ targets
      }.taskValue,
      cleanFiles += {
        (Compile / resourceDirectory).value / "dar"
      },
    )
  }

  lazy val removeCompileFlagsForDaml =
    Seq(
      "-deprecation",
      "-Xfatal-warnings",
      "-Wunused:implicits",
      "-Wunused:imports",
      "-Wunused:locals",
      "-Wunused:nowarn",
      "-Ywarn-value-discard",
      "-Wnonunit-statement",
      "-Xlint:_,-unused",
    )

  lazy val sbtSettings: Seq[Def.Setting[_]] = {

    def alsoTest(taskName: String) = s";$taskName; Test / $taskName"

    val globalSettings = Seq(
      name := "splice-node",
      // Automatically reload sbt project when sbt build definition files change
      Global / onChangedBuildSource := ReloadOnSourceChanges,
      // allow setting number of tasks via environment
      Global / concurrentRestrictions ++= sys.env
        .get("MAX_CONCURRENT_SBT_TEST_TASKS")
        .map(_.toInt)
        .map(Tags.limit(Tags.Test, _))
        .toSeq,
      // Limit the number of concurrent damlTest tasks as they are pretty heavy on the RAM
      // Number chosen to work well in CI on our Large executor, and our dev machines
      // See #9400 for more details.
      Global / concurrentRestrictions += Tags.limit(damlTestTag, 4),
      // copied from the Canton OSS repo
      Global / excludeLintKeys += Compile / damlBuildOrder,
      Global / excludeLintKeys += `canton-blake2b` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-app` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-app` / Compile / damlDarLfVersion,
      Global / excludeLintKeys += `canton-community-common` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-synchronizer` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-participant` / autoAPIMappings,
      //      Global / excludeLintKeys += `demo` / autoAPIMappings,
      Global / excludeLintKeys += `canton-slick-fork` / autoAPIMappings,
      Global / excludeLintKeys += Global / damlCodeGeneration,
    )

    val commandAliases =
      addCommandAlias(
        "scalafixCheck",
        s"${alsoTest("scalafix --check")}",
      ) ++
        addCommandAlias(
          "format",
          s"; scalafmt ; Test / scalafmt ; scalafmtSbt",
        ) ++
        addCommandAlias(
          "formatFix",
          s"; format ; scalafixAll ; apps-frontends/npmFix ; pulumi/npmFix ; load-tester/npmFix ; headerCreate",
        ) ++
        addCommandAlias(
          "lint",
          "; damlDarsLockFileCheck ; scalafmtCheck ; Test / scalafmtCheck ; scalafmtSbtCheck ; scalafixAll ; apps-frontends/npmLint ; pulumi/npmLint ; load-tester/npmLint ; party-allocator/npmLint ; runShellcheck ; syncpackCheck ; illegalDamlReferencesCheck ; headerCheck",
        ) ++
        // it might happen that some DARs remain dangling on build config changes,
        // so we explicitly remove all Splice DARs here, just in case
        addCommandAlias(
          "clean-splice",
          Seq(
            "apps-common/clean",
            "apps-common-sv/clean",
            "apps-validator/clean",
            "apps-scan/clean",
            "apps-splitwell/clean",
            "apps-sv/clean",
            "apps-wallet/clean",
            "apps-app/clean",
            "splice-amulet-daml/clean",
            "splice-amulet-name-service-daml/clean",
            "splice-amulet-name-service-test-daml/clean",
            "splice-amulet-test-daml/clean",
            "splice-dso-governance-daml/clean",
            "splice-dso-governance-test-daml/clean",
            "splice-util-daml/clean",
            "splice-validator-lifecycle-daml/clean",
            "splice-validator-lifecycle-test-daml/clean",
            "splice-wallet-daml/clean",
            "splice-wallet-payments-daml/clean",
            "splice-wallet-test-daml/clean",
            "splitwell-daml/clean",
            "splitwell-test-daml/clean",
            "splice-api-token-metadata-v1-daml/clean",
            "splice-api-token-holding-v1-daml/clean",
            "splice-api-token-transfer-instruction-v1-daml/clean",
            "splice-api-token-allocation-v1-daml/clean",
            "splice-api-token-allocation-request-v1-daml/clean",
            "splice-api-token-allocation-instruction-v1-daml/clean",
            "splice-token-standard-test-daml/clean",
            "apps-frontends/clean",
            "cleanCnDars",
            "docs/clean",
          ).map(";" + _).mkString(""),
        ) ++
        addCommandAlias("splice-clean", "; clean-splice")
    val buildSettings = inThisBuild(
      Seq(
        organization := "org.lfdecentralizedtrust.splice",
        scalaVersion := scala_version,
        // , scalacOptions += "-Ystatistics" // re-enable if you need to debug compile times
      )
    )

    buildSettings ++ globalSettings ++ commandAliases
  }

  // Not used in `canton/` copies right now due to a stackoverflow error that would require further investigation
  lazy val cantonWarts = Seq(
    wartremoverErrors += Wart.custom("com.digitalasset.canton.DiscardedFuture"),
    wartremoverErrors += Wart.custom("com.digitalasset.canton.RequireBlocking"),
    wartremoverErrors += Wart.custom("com.digitalasset.canton.FutureTraverse"),
    wartremover.WartRemover.dependsOnLocalProjectWarts(
      `canton-wartremover-extension`
    ),
  ).flatMap(_.settings)

  lazy val unusedImportsSetting: Seq[Def.Setting[_]] =
    // Unused imports can be annoying during development so we allow
    // turning them into an info summary.
    // Using a file, instead of, e.g., an environment variable because it's not possible to set
    // custom environment variables for the sbt-shell used by IntelliJ (https://youtrack.jetbrains.com/issue/SCL-19025)
    if (better.files.File(".disable-unused-warnings").exists)
      Seq(
        scalacOptions += "-Wconf:cat=unused-imports:is,cat=unused-locals:is,cat=unused-params:is,cat=unused-pat-vars:is,cat=unused-privates:is,cat=unused-params:is"
      )
    else Seq.empty

  // Settings to avoid compiling test sources, applicable to canton projects
  lazy val removeTestSources = Seq(
    Test / managedSources := Seq.empty,
    Test / unmanagedSources := Seq.empty,
  )

  // Settings to disable tests for canton projects, so we don't run them when running our tests.
  lazy val disableTests = Seq(
    Compile / testOnly := {},
    Test / testOnly := {},
    testOnly := {},
    Compile / test := {},
    Test / test := {},
    testOnly := {},
    Test / definedTests := Seq.empty,
  )

  // applies to all Canton-based sub-projects (descendants of community-common)
  lazy val sharedCantonSettings = Seq(
    // Enable logging of begin and end of test cases, test suites, and test runs.
    Test / testOptions += Tests
      .Argument("-C", "com.digitalasset.canton.LogReporter"),
    // Commented out from Canton OS repo because we don't have code coverage tests yet
    //    // Ignore daml codegen generated files from code coverage
    //    coverageExcludedFiles := formatCoverageExcludes(
    //      """
    //        |<empty>
    //        |.*sbt-buildinfo.BuildInfo
    //        |.*daml-codegen.*
    //      """
    //    ),
    scalacOptions ++= Seq(
      "-Wconf:src=src_managed/.*:silent",
      // disable scala 3 migration warnings for canton as we're not gonna fix those
      "-Wconf:cat=scala3-migration:silent",
      "-Wconf:msg=Name .* is already introduced in an enclosing scope as .*:silent",
      "-Wconf:msg=@nowarn annotation does not suppress any warnings:silent",
    ),
    headerSources / excludeFilter := "*",
    headerResources / excludeFilter := "*",
  ) ++ sharedProtocSettings ++ Headers.NoHeaderSettings

  // Project for utilities that are also used outside of the Canton repo
  lazy val `canton-util-external` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-util-external", file("canton/base/util-external"))
      .dependsOn(
        `canton-pekko-fork`,
        `canton-magnolify-addon`,
        `canton-wartremover-extension` % "compile->compile;test->test",
        `canton-util-observability`,
        // Canton depends on the Daml code via a git submodule and the two
        // projects below. We instead depend on the artifacts released
        // from the Daml repo listed in libraryDependencies below.
        // `daml-copy-common`,
        // `daml-copy-testing` % "test->test",
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          daml_metrics,
          daml_tracing,
          daml_executors,
          daml_lf_data,
          daml_nonempty_cats,
          logback_classic,
          logback_core,
          scala_logging,
          scala_collection_contrib,
          scalatest % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          cats,
          jul_to_slf4j % Test,
          log4j_core,
          log4j_api,
          monocle_macro, // Include it here, even if unused, so that it can be used everywhere
          pureconfig, // Only dependencies may be needed, but it is simplest to include it like this
          opentelemetry_api,
          opentelemetry_sdk,
          opentelemetry_sdk_autoconfigure,
          opentelemetry_instrumentation_grpc,
          opentelemetry_zipkin,
        ),
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        // JvmRulesPlugin.damlRepoHeaderSettings,
      )
  }

  lazy val `canton-base-errors` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-base-errors", file("canton/base/errors"))
      .dependsOn(
        `canton-google-common-protos-scala`,
        `canton-wartremover-extension` % "compile->compile;test->test",
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          slf4j_api,
          grpc_api,
          reflections,
          scalatest % Test,
          scalacheck % Test,
          scalatestScalacheck % Test,
        ),
      )
  }

  lazy val `canton-daml-tls` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-tls", file("canton/base/daml-tls"))
      .dependsOn(
        `canton-util-internal`,
        `canton-wartremover-extension` % "compile->compile;test->test",
        `canton-util-observability`,
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          grpc_netty_shaded,
          netty_handler,
          netty_boring_ssl, // This should be a Runtime dep, but needs to be declared at Compile scope due to https://github.com/sbt/sbt/issues/5568
          scopt,
          apache_commons_io % "test",
        ),
      )
  }

  lazy val `canton-daml-adjustable-clock` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-adjustable-clock", file("canton/base/adjustable-clock"))
      .settings(
        sharedCantonSettings
      )
  }

  lazy val `canton-daml-grpc-utils` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-grpc-utils", file("canton/base/grpc-utils"))
      .dependsOn(
        `canton-google-common-protos-scala`
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          grpc_api,
          scalapb_runtime_grpc,
          scalatest % Test,
        ),
      )
  }

  lazy val `canton-daml-jwt` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-jwt", file("canton/base/daml-jwt"))
      .disablePlugins(WartRemover)
      .settings(
        sharedSettings,
        libraryDependencies ++= Seq(
          auth0_java,
          auth0_jwks,
          daml_http_test_utils % Test,
          daml_libs_struct_spray_json,
          daml_test_evidence_generator_scalatest % Test,
          scalatest % Test,
          scalaz_core,
          slf4j_api,
        ),
      )
  }

  // Project for general utilities used inside the Canton repo only
  lazy val `canton-util-internal` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-util-internal", file("canton/community/util"))
      .dependsOn(
        `canton-util-external`
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          logback_classic,
          logback_core,
          scala_logging,
          scala_collection_contrib,
          scalatest % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          cats,
          cats_law % Test,
          jul_to_slf4j % Test,
          log4j_core,
          log4j_api,
          monocle_macro, // Include it here, even if unused, so that it can be used everywhere
          pureconfig, // Only dependencies may be needed, but it is simplest to include it like this
        ),
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        // JvmRulesPlugin.damlRepoHeaderSettings,
      )
  }

  lazy val `canton-util-observability` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-util-observability", file("canton/community/util-observability"))
      .dependsOn(
        `canton-base-errors` % "compile->compile;test->test",
        `canton-daml-grpc-utils`,
        `canton-wartremover-extension` % "compile->compile;test->test",
      )
      .settings(
        sharedCantonSettings,
        sharedSettings ++ cantonWarts,
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        libraryDependencies ++= Seq(
          better_files,
          daml_lf_data,
          daml_nonempty_cats,
          daml_metrics,
          daml_tracing,
          daml_contextualized_logging,
          logback_classic,
          logback_core,
          scala_logging,
          log4j_core,
          log4j_api,
          opentelemetry_api,
          opentelemetry_sdk,
          opentelemetry_sdk_autoconfigure,
          opentelemetry_instrumentation_grpc,
          opentelemetry_zipkin,
          opentelemetry_trace,
        ),
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
      )
  }

  lazy val `canton-community-app` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-app", file("canton/community/app"))
      .dependsOn(
        `canton-community-app-base`,
        `canton-community-common` % "compile->compile;test->test",
        `canton-community-synchronizer`,
        `canton-community-participant`,
        `canton-community-integration-testing` % "test",
        `canton-ledger-api-core` % "test->test",
      )
      .enablePlugins(DamlPlugin)
      .settings(
        sharedCantonSettings,
        // commented out from Canton OS repo as settings don't apply to us
        //      sharedAppSettings,
        disableTests,
        removeTestSources,
        libraryDependencies ++= Seq(
          scala_logging,
          jul_to_slf4j,
          janino, // not used at compile time, but required for conditionals in logback configuration
          logstash, // not used at compile time, but required for the logback json encoder
          scalatest % Test,
          scalacheck % Test,
          scalatestScalacheck % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          scopt,
          logback_classic,
          logback_core,
          pekko_stream_testkit % Test,
          pekko_http,
          pekko_http_testkit % Test,
          pureconfig_cats,
          cats,
          better_files,
          toxiproxy_java % Test,
        ),
        // commented out from Canton OS repo as settings because they don't apply to us
        // core packaging commands
        //      bundlePack := sharedAppPack,
        //      additionalBundleSources := Seq.empty,
        //      assembly / mainClass := Some("com.digitalasset.canton.CantonCommunityApp"),
        //      assembly / assemblyJarName := s"canton-open-source-${version.value}.jar",
        // clearing the damlBuild tasks to prevent compiling which does not work due to relative file "data-dependencies";
        // "data-dependencies" daml.yaml setting relies on hardcoded "0.0.1" project version
        Compile / damlBuild := Seq(), // message-0.0.1.dar is hardcoded and contact-0.0.1.dar is built by MessagingExampleIntegrationTest
        Test / damlBuild := Seq(),
        Test / damlTest := Seq(),
        Compile / damlProjectVersionOverride := Some("0.0.1"),
        Compile / damlEnableJavaCodegen := true,
        Compile / damlCodegenUseProject := false,
        Headers.NoHeaderSettings,
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      addProtobufFilesToHeaderCheck(Compile),
        //      addFilesToHeaderCheck("*.sh", "../pack", Compile),
        //      addFilesToHeaderCheck("*.sh", ".", Test),
        //      JvmRulesPlugin.damlRepoHeaderSettings,
      )
  }

  lazy val `canton-community-app-base` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-app-base", file("canton/community/app-base"))
      .dependsOn(
        `canton-community-synchronizer`,
        `canton-community-participant`,
      )
      .settings(
        removeTestSources,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          pekko_http,
          ammonite,
          jul_to_slf4j,
          pureconfig_cats,
        ),
      )
  }

  // The purpose of this module is to collect `compile`-scoped classes shared by `community-testing` (which
  // is in turn meant to be imported at `test` scope) as well as other projects which need it in `compile`
  // scope. This is to avoid cyclic dependencies. As such, this module should _not_ depend on anything internal,
  // possibly with the exception of `util-internal` and/or `util-external`.
  // In principle this might be merged into `util-external` at a later time, but this is separate for the time
  // being to ensure a clean separation of modules.
  lazy val `canton-community-base` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-base", file("canton/community/base"))
      .enablePlugins(BuildInfoPlugin)
      .dependsOn(
        `canton-slick-fork`,
        `canton-util-external`,
        `canton-daml-grpc-utils`,
        `canton-daml-jwt`,
        `canton-daml-tls`,
        `canton-ledger-common`,
        `canton-bindings-java`,
        `canton-community-admin-api`,
        `canton-kms-driver-api`,
        `canton-scalatest-addon` % "compile->test",
        // Canton depends on the Daml code via a git submodule and the two
        // projects below. We instead depend on the artifacts released
        // from the Daml repo listed in libraryDependencies below.
        // `daml-copy-common`,
        // No strictly internal dependencies on purpose so that this can be a foundational module and avoid circular dependencies
      )
      .settings(
        removeTestSources,
        sharedCantonSettings,
        // commented out from Canton OS repo as settings don't apply to us (yet)
        // JvmRulesPlugin.damlRepoHeaderSettings,
        libraryDependencies ++= Seq(
          better_files,
          bouncycastle_bcpkix_jdk15on,
          bouncycastle_bcprov_jdk15on,
          cats,
          chimney,
          circe_core,
          circe_generic,
          flyway.excludeAll(ExclusionRule("org.apache.logging.log4j")),
          flyway_postgresql,
          grpc_services,
          postgres,
          pprint,
          scaffeine,
          tink,
          daml_nonempty_cats,
          daml_lf_transaction,
          CantonDependencies.grpc_services % "protobuf",
          scalapb_runtime_grpc,
          scalapb_runtime,
          slick_hikaricp,
          CantonDependencies.opentelemetry_instrumentation_runtime_metrics,
          CantonDependencies.opentelemetry_instrumentation_hikari,
        ),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
        ),
        // Ensure the package scoped options will be picked up by sbt-protoc if used downstream
        // See https://scalapb.github.io/docs/customizations/#publishing-package-scoped-options
        Compile / packageBin / packageOptions += (
          Package.ManifestAttributes(
            "ScalaPB-Options-Proto" -> "com/digitalasset/canton/scalapb/package.proto"
          )
        ),
        buildInfoKeys := Seq[BuildInfoKey](
          BuildInfoKey("version", version), // hacked.
          scalaVersion,
          sbtVersion,
          BuildInfoKey("damlLibrariesVersion" -> CantonDependencies.daml_libraries_version),
          BuildInfoKey("stableProtocolVersions" -> List()),
          BuildInfoKey("betaProtocolVersions" -> List()),
        ),
        buildInfoPackage := "com.digitalasset.canton.buildinfo",
        buildInfoObject := "BuildInfo",
        // commented out from Canton OS repo as we don't have code coverage (yet)
        // excluded generated protobuf classes from code coverage
        //   coverageExcludedPackages := formatCoverageExcludes(
        //     """
        //       |<empty>
        //       |com\.digitalasset\.canton\.protocol\.v0\..*
        //       |com\.digitalasset\.canton\.domain\.v0\..*
        //       |com\.digitalasset\.canton\.identity\.v0\..*
        //       |com\.digitalasset\.canton\.identity\.admin\.v0\..*
        //       |com\.digitalasset\.canton\.domain\.api\.v0\..*
        //       |com\.digitalasset\.canton\.v0\..*
        //       |com\.digitalasset\.canton\.protobuf\..*
        // """
        //   ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        // addProtobufFilesToHeaderCheck(Compile),
      )
  }

  lazy val `canton-community-testing` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-testing", file("canton/community/testing"))
      .disablePlugins(WartRemover)
      .dependsOn(
        `canton-community-base`
      )
      .settings(
        sharedCantonSettings,
        removeTestSources,
        sharedSettings,
        // JvmRulesPlugin.damlRepoHeaderSettings,
        libraryDependencies ++= Seq(
          better_files,
          cats,
          cats_law,
          daml_metrics_test_lib,
          jul_to_slf4j,
          mockito_scala,
          opentelemetry_api,
          scalatest,
          scalatestScalacheck,
          testcontainers,
          testcontainers_postgresql,
        ),

        // This library contains a lot of testing helpers that previously existing in testing scope
        // As such, in order to minimize the diff when creating this library, the same rules that
        // applied to `test` scope are used here. This can be reviewed in the future.
        scalacOptions --= JvmRulesPlugin.scalacOptionsToDisableForTests,
      )
  }

  lazy val `canton-community-integration-testing` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-integration-testing", file("canton/community/integration-testing"))
      .disablePlugins(WartRemover)
      .dependsOn(
        `canton-community-app-base`,
        `canton-community-testing`,
        `canton-community-reference-driver`,
      )
      .settings(
        sharedCantonSettings,

        // The dependency override is needed because `community-testing` depends transitively on
        // `scalatest` and `community-app-base` depends transitively on `ammonite`, which in turn
        // depend on incompatible versions of `scala-xml` -- not ideal but only causes possible
        // runtime errors while testing and none have been found so far, so this should be fine for now
        dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "2.0.1",
        libraryDependencies ++= Seq(
          toxiproxy_java,
          opentelemetry_proto,
          daml_http_test_utils,
        ),

        // This library contains a lot of testing helpers that previously existing in testing scope
        // As such, in order to minimize the diff when creating this library, the same rules that
        // applied to `test` scope are used here. This can be reviewed in the future.
        scalacOptions --= JvmRulesPlugin.scalacOptionsToDisableForTests,
      )
  }

  lazy val `canton-community-common` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-common", file("canton/community/common"))
      .enablePlugins(BuildInfoPlugin, DamlPlugin)
      .dependsOn(
        `canton-blake2b`,
        `canton-pekko-fork` % "compile->compile;test->test",
        `canton-magnolify-addon`,
        `canton-community-base`,
        `canton-wartremover-extension` % "compile->compile;test->test",
        `canton-util-external` % "compile->compile;test->test",
        `canton-community-testing` % "test",
        `canton-ledger-common` % "compile->compile;test->test",
      )
      .settings(
        removeTestSources,
        // We only need 3 files out of a lot of test files so add them explicitly
        Test / managedSources := Seq(
          (Test / sourceDirectory).value / "scala/com/digitalasset/canton/HasActorSystem.scala",
          (Test / sourceDirectory).value / "scala/com/digitalasset/canton/store/db/DbTest.scala",
          (Test / sourceDirectory).value / "scala/com/digitalasset/canton/store/db/DbStorageIdempotency.scala",
        ),
        disableTests,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          pekko_http,
          pekko_http_core,
          pekko_slf4j, // not used at compile time, but required by com.digitalasset.canton.util.pekkoUtil.createActorSystem
          daml_lf_archive_reader,
          daml_lf_engine,
          daml_lf_transaction, // needed for importing java classes
          daml_nonempty_cats,
          logback_classic,
          logback_core,
          scala_logging,
          scala_collection_contrib,
          scalatest % Test,
          scalacheck % Test,
          scalatestScalacheck % Test,
          cats_scalacheck % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          magnolia % Test,
          magnolify_scalacheck % Test,
          magnolify_shared % Test,
          daml_lf_transaction % Test,
          daml_lf_transaction_test_lib % Test,
          daml_test_evidence_tag % Test,
          daml_test_evidence_scalatest % Test,
          daml_test_evidence_generator_scalatest % Test,
          better_files,
          cats,
          cats_law % Test,
          chimney,
          circe_core,
          circe_generic,
          circe_generic_extras,
          jul_to_slf4j % Test,
          bouncycastle_bcprov_jdk15on,
          bouncycastle_bcpkix_jdk15on,
          grpc_netty_shaded,
          grpc_services,
          scalapb_runtime_grpc,
          scalapb_runtime,
          log4j_core,
          log4j_api,
          flyway excludeAll (ExclusionRule("org.apache.logging.log4j")),
          flyway_postgresql,
          h2,
          tink,
          slick,
          slick_hikaricp,
          testcontainers % Test,
          testcontainers_postgresql % Test,
          postgres,
          sttp,
          sttp_okhttp,
          sttp_circe,
          monocle_macro, // Include it here, even if unused, so that it can be used everywhere
          pprint,
          pureconfig, // Only dependencies may be needed, but it is simplest to include it like this
          opentelemetry_api,
          opentelemetry_sdk,
          opentelemetry_sdk_autoconfigure,
          opentelemetry_instrumentation_grpc,
          opentelemetry_zipkin,
          opentelemetry_prometheus,
          scaffeine,
        ),
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
        ),
        Compile / PB.protoSources ++= (Test / PB.protoSources).value,
        // commented out from Canton OS repo as we don't have code coverage (yet)
        //    // excluded generated protobuf classes from code coverage
        //    coverageExcludedPackages := formatCoverageExcludes(

        //      """
        //        |<empty>
        //        |com\.digitalasset\.canton\.protocol\.v0\..*
        //        |com\.digitalasset\.canton\.domain\.v0\..*
        //        |com\.digitalasset\.canton\.identity\.v0\..*
        //        |com\.digitalasset\.canton\.identity\.admin\.v0\..*
        //        |com\.digitalasset\.canton\.domain\.api\.v0\..*
        //        |com\.digitalasset\.canton\.v0\..*
        //        |com\.digitalasset\.canton\.protobuf\..*
        //      """
        //    ),
        Compile / damlCodeGeneration := {
          val Seq(darFile, _) = (Compile / damlBuild).value
          Seq(
            (
              (Compile / baseDirectory).value,
              darFile,
              "com.digitalasset.canton.examples",
            )
          )
        },
        Test / damlTest := Seq(),
        Compile / damlEnableJavaCodegen := true,
        Compile / damlCodegenUseProject := false,
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //    addProtobufFilesToHeaderCheck(Compile),
        //    addFilesToHeaderCheck("*.daml", "daml", Compile),
        //    JvmRulesPlugin.damlRepoHeaderSettings
      )
  }

  lazy val `canton-community-synchronizer` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-synchronizer", file("canton/community/synchronizer"))
      .dependsOn(
        `canton-community-common` % "compile->compile;test->test",
        `canton-community-admin-api` % "compile->compile;test->test",
        `canton-sequencer-driver-api`,
        `canton-community-reference-driver`,
      )
      .settings(
        removeTestSources,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          pekko_actor_typed,
          scala_logging,
          scalatest % Test,
          scalacheck % Test,
          scalatestScalacheck % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          logback_classic % Runtime,
          logback_core % Runtime,
          scalapb_runtime, // not sufficient to include only through the `common` dependency - race conditions ensue
          scaffeine,
          oracle,
        ),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
        ),
        // commented out from Canton OS repo as we don't have code coverage tests (yet)
        //      // excluded generated protobuf classes from code coverage
        //      coverageExcludedPackages := formatCoverageExcludes(
        //        """
        //          |<empty>
        //          |com\.digitalasset\.canton\.domain\.admin\.v0\..*
        //      """
        //      ),
        //      addProtobufFilesToHeaderCheck(Compile),
        //      JvmRulesPlugin.damlRepoHeaderSettings,
      )
  }

  lazy val `canton-community-admin-api` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-admin-api", file("canton/community/admin-api"))
      .dependsOn(`canton-util-external`, `canton-daml-errors` % "compile->compile;test->test")
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          scalapb_runtime // not sufficient to include only through the `common` dependency - race conditions ensue
        ),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
        ),
        // Ensure the package scoped options will be picked up by sbt-protoc if used downstream
        // See https://scalapb.github.io/docs/customizations/#publishing-package-scoped-options
        Compile / packageBin / packageOptions += (
          Package.ManifestAttributes(
            "ScalaPB-Options-Proto" -> "com/digitalasset/canton/admin/scalapb/package.proto"
          )
        ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        // addProtobufFilesToHeaderCheck(Compile),
      )
  }

  lazy val `canton-community-participant` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-participant", file("canton/community/participant"))
      .dependsOn(
        `canton-community-common` % "compile->compile;test->test",
        `canton-ledger-api-core` % "compile->compile;test->test",
        `canton-ledger-json-api`,
        `canton-community-admin-api`,
      )
      .enablePlugins(DamlPlugin)
      .settings(
        removeTestSources,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          scala_logging,
          scalatest % Test,
          scalatestScalacheck % Test,
          scalacheck % Test,
          daml_lf_archive_reader,
          daml_lf_engine,
          logback_classic % Runtime,
          logback_core % Runtime,
          pekko_stream,
          pekko_stream_testkit % Test,
          cats,
          chimney,
          scalapb_runtime, // not sufficient to include only through the `common` dependency - race conditions ensue
        ),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
        ),
        // commented out from Canton OS repo as we don't have code coverage tests (yet)
        //      coverageExcludedPackages := formatCoverageExcludes(
        //        """
        //          |<empty>
        //          |com\.digitalasset\.canton\.participant\.admin\.v0\..*
        //          |com\.digitalasset\.canton\.participant\.protocol\.v0\..*
        //      """
        //      ),
        Compile / damlSourceDirectory := sourceDirectory.value / "main",
        Test / damlTest := Seq(),
        Compile / damlCodeGeneration :=
          Seq(
            (
              (Compile / sourceDirectory).value / "daml" / "canton-builtin-admin-workflow-ping",
              (Compile / damlDarOutput).value / "canton-builtin-admin-workflow-ping-current.dar",
              "com.digitalasset.canton.participant.admin.workflows",
            ),
            (
              (Compile / sourceDirectory).value / "daml" / "canton-builtin-admin-workflow-party-replication-alpha",
              (Compile / damlDarOutput).value / "canton-builtin-admin-workflow-party-replication-alpha-current.dar",
              "com.digitalasset.canton.participant.admin.workflows",
            ),
          ),
        Compile / damlEnableJavaCodegen := true,
        Compile / damlCodegenUseProject := false,
        Compile / damlBuildOrder := Seq(
          "daml/canton-builtin-admin-workflow-ping/daml.yaml",
          "daml/canton-builtin-admin-workflow-party-replication-alpha/daml.yaml",
        ),
        // TODO(DACH-NY/canton-network-node#16168) Before creating the first stable release with backwards compatibility guarantees,
        //  make "AdminWorkflows.dar" stable again
        damlFixedDars := Seq(),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      addProtobufFilesToHeaderCheck(Compile),
        //      addFilesToHeaderCheck("*.daml", "daml", Compile),
        //      JvmRulesPlugin.damlRepoHeaderSettings,
      )
  }

  lazy val `canton-blake2b` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-blake2b", file("canton/community/lib/Blake2b"))
      .disablePlugins(ScalafmtPlugin, WartRemover)
      .settings(
        sharedCantonSettings,
        removeTestSources,
        sharedSettings,
        libraryDependencies ++= Seq(
          bouncycastle_bcprov_jdk15on,
          bouncycastle_bcpkix_jdk15on,
        ),
      )
  }

  lazy val `canton-slick-fork` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-slick-fork", file("canton/community/lib/slick"))
      .disablePlugins(ScalafmtPlugin, WartRemover)
      .settings(
        sharedCantonSettings,
        removeTestSources,
        sharedSettings,
        libraryDependencies ++= Seq(
          scala_reflect,
          slick,
        ),
      )
  }

  lazy val `canton-wartremover-extension` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-wartremover-extension", file("canton/community/lib/wartremover"))
      .dependsOn(`canton-wartremover-annotations`, `canton-slick-fork`)
      .settings(
        disableTests,
        sharedSettings,
        libraryDependencies ++= Seq(
          cats,
          grpc_stub,
          mockito_scala % Test,
          scalapb_runtime_grpc,
          scalatestMockito % Test,
          scalatest % Test,
          slick,
          wartremover_dep,
        ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      // Exclude to apply our license header to any Scala files
        //      headerSources / excludeFilter := "*.scala",
        //      coverageEnabled := false,
      )
  }

  lazy val `canton-wartremover-annotations` =
    sbt.Project
      .apply("canton-wartremover-annotations", file("canton/community/lib/wartremover-annotations"))
      .settings(sharedSettings)

  // https://github.com/DACH-NY/canton/issues/10617: remove when no longer needed
  lazy val `canton-pekko-fork` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-pekko-fork", file("canton/community/lib/pekko"))
      .disablePlugins(ScalafixPlugin, ScalafmtPlugin, WartRemover)
      .settings(
        sharedCantonSettings,
        sharedSettings,
        libraryDependencies ++= Seq(
          pekko_stream,
          pekko_stream_testkit % Test,
          pekko_slf4j,
          scalatest % Test,
        ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      // Exclude to apply our license header to any Scala files
        //      headerSources / excludeFilter := "*.scala",
        //      coverageEnabled := false,
      )
  }

  lazy val `canton-magnolify-addon` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-magnloify-addon", file("canton/community/lib/magnolify"))
      .settings(
        sharedSettings,
        libraryDependencies ++= Seq(
          cats,
          daml_nonempty,
          magnolia,
          magnolify_scalacheck,
          magnolify_shared % Test,
          scala_reflect,
          scalacheck,
          scalatest % Test,
        ),
      )

  }

  lazy val `canton-scalatest-addon` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-scalatest-addon", file("canton/community/lib/scalatest"))
      .settings(
        sharedSettings,
        libraryDependencies += scalatest,
        // Exclude to apply our license header to any Scala files
        headerSources / excludeFilter := "*.scala",
      )
  }

  lazy val `canton-daml-errors` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-errors", file("canton/base/daml-errors"))
      .dependsOn(
        `canton-wartremover-extension` % "compile->compile;test->test",
        `canton-google-common-protos-scala`,
      )
      .settings(
        sharedCantonSettings,
        sharedSettings ++ cantonWarts,
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        libraryDependencies ++= Seq(
          slf4j_api,
          grpc_api,
          reflections,
          scalatest % Test,
          scalacheck % Test,
          scalatestScalacheck % Test,
        ),
      )
  }

  lazy val `canton-ledger-common` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-ledger-common", file("canton/community/ledger/ledger-common"))
      .disablePlugins(WartRemover, ScalafmtPlugin)
      .dependsOn(
        `canton-util-external`,
        `canton-daml-errors` % "compile->compile;test->test",
        `canton-bindings-java` % "compile->compile;test->test",
        `canton-daml-grpc-utils`,
        `canton-daml-jwt`,
        `canton-util-observability`,
        `canton-ledger-api`,
      )
      .settings(
        removeTestSources,
        sharedCantonSettings,
        disableTests,
        sharedSettings,
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        Compile / PB.targets := Seq(
          PB.gens.java -> (Compile / sourceManaged).value / "protobuf",
          scalapb.gen(flatPackage = false) -> (Compile / sourceManaged).value / "protobuf",
        ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      addProtobufFilesToHeaderCheck(Compile),
        libraryDependencies ++= Seq(
          daml_contextualized_logging,
          daml_lf_engine,
          daml_lf_archive_reader,
          daml_tracing,
          apache_commons_codec,
          apache_commons_io,
          daml_ledger_resources,
          daml_timer_utils,
          daml_rs_grpc_pekko,
          opentelemetry_api,
          pekko_stream,
          slf4j_api,
          grpc_api,
          reflections,
          grpc_netty_shaded,
          netty_boring_ssl, // This should be a Runtime dep, but needs to be declared at Compile scope due to https://github.com/sbt/sbt/issues/5568
          netty_handler,
          caffeine,
          scalapb_runtime,
          scalapb_runtime_grpc,
          scopt,
          awaitility % Test,
          logback_classic % Test,
          scalatest % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          pekko_stream_testkit % Test,
          scalacheck % Test,
          opentelemetry_sdk_testing % Test,
          scalatestScalacheck % Test,
          daml_lf_data,
          daml_lf_transaction,
          daml_http_test_utils % Test,
          daml_testing_utils % Test,
          daml_ports % Test,
          daml_tracing_test_lib % Test,
          daml_rs_grpc_testing_utils % Test,
        ),
        Test / fork := true,
        Test / testForkedParallel := true,
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      coverageEnabled := false,
        //      JvmRulesPlugin.damlRepoHeaderSettings,
      )
  }

  lazy val `canton-ledger-api-core` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-ledger-api-core", file("canton/community/ledger/ledger-api-core"))
      .dependsOn(
        `canton-base-errors` % "test->test",
        `canton-ledger-common` % "compile->compile;test->test",
        `canton-community-common` % "compile->compile;test->test",
        `canton-daml-adjustable-clock` % "test->test",
        `canton-daml-errors` % "test->test",
        `canton-daml-tls` % "test->test",
      )
      .disablePlugins(
        WartRemover,
        ScalafmtPlugin,
      ) // to accommodate different daml repo coding style
      .settings(
        removeTestSources,
        sharedCantonSettings,
        sharedSettings,
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = false) -> (Compile / sourceManaged).value / "protobuf"
        ),
        libraryDependencies ++= Seq(
          auth0_java,
          auth0_jwks,
          circe_core,
          daml_libs_scala_grpc_test_utils,
          daml_ports,
          daml_struct_spray_json,
          netty_boring_ssl,
          netty_handler,
          hikaricp,
          guava,
          bouncycastle_bcprov_jdk15on % Test,
          bouncycastle_bcpkix_jdk15on % Test,
          scalaz_scalacheck % Test,
          grpc_netty_shaded,
          grpc_services,
          grpc_protobuf,
          postgres,
          h2,
          flyway,
          oracle,
          anorm,
          scalapb_runtime_grpc,
          scalapb_json4s % Test,
          scalapb_runtime,
          scalaz_scalacheck % Test,
          testcontainers % Test,
          testcontainers_postgresql % Test,
        ),
        Test / parallelExecution := true,
        Test / fork := false,
      )
  }

  // this project builds scala protobuf versions that include
  // java conversions of a few google standard items
  // the google protobuf files are extracted from the provided jar files
  lazy val `canton-google-common-protos-scala` = {
    import CantonDependencies._
    sbt.Project
      .apply(
        "canton-google-common-protos-scala",
        file("canton/community/lib/google-common-protos-scala"),
      )
      .disablePlugins(
        ScalafixPlugin,
        ScalafmtPlugin,
        WartRemover,
      )
      .settings(
        sharedCantonSettings,
        scalacOptions --= removeCompileFlagsForDaml,
        sharedSettings,
        // we restrict the compilation to a few files that we actually need, skipping the large majority ...
        excludeFilter := HiddenFileFilter || "scalapb.proto",
        PB.generate / includeFilter := "status.proto" || "code.proto" || "error_details.proto" || "health.proto",
        dependencyOverrides ++= Seq(),
        // compile proto files that we've extracted here
        Compile / PB.protoSources += (target.value / "protobuf_external"),
        Compile / PB.targets := Seq(
          // with java conversions but no java classes!
          scalapb.gen(
            javaConversions = true,
            flatPackage = false, // consistent with upstream daml
          ) -> (Compile / sourceManaged).value
        ),
        libraryDependencies ++= Seq(
          scalapb_runtime,
          scalapb_runtime_grpc,
          // the grpc services is necessary so we can build the
          // scala version of the health services, without
          // building the java protoc (to avoid duplicate symbols
          // during assembly)
          grpc_services,
          // extract the protobuf to target/protobuf_external
          // however, we'll only be including the ones in the includeFilter
          grpc_services % "protobuf",
          google_common_protos % "protobuf",
          google_common_protos,
          google_protobuf_java,
          google_protobuf_java_util,
        ),
      )
  }

  // this project exists solely for the purpose of extracting value.proto
  // from the jar file built in the daml repository
  lazy val `canton-ledger-api-value` = project
    .in(file("community/lib/ledger-api-value"))
    .disablePlugins(
      ScalafixPlugin,
      ScalafmtPlugin,
      WartRemover,
    )
    .settings(
      sharedCantonSettings,
      sharedSettings,
      // we restrict the compilation to a few files that we actually need, skipping the large majority ...
      excludeFilter := HiddenFileFilter || "scalapb.proto",
      PB.generate / includeFilter := "value.proto",
      dependencyOverrides ++= Seq(),
      // compile proto files that we've extracted here
      Compile / PB.protoSources ++= Seq(target.value / "protobuf_external"),
      libraryDependencies ++= Seq(
        daml_ledger_api_value_proto % "protobuf"
      ),
    )

  lazy val `canton-ledger-api` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-ledger-api", file("canton/community/ledger-api"))
      .dependsOn(`canton-google-common-protos-scala`, `canton-ledger-api-value`)
      .disablePlugins(
        ScalafixPlugin,
        ScalafmtPlugin,
        WartRemover,
      )
      .settings(
        sharedCantonSettings,
        scalacOptions --= removeCompileFlagsForDaml,
        scalacOptions += "-Wconf:cat=deprecation:s",
        sharedSettings,
        Compile / PB.targets := Seq(
          // build java codegen too
          PB.gens.java -> (Compile / sourceManaged).value,
          // build scala codegen with java conversions
          scalapb.gen(
            javaConversions = true,
            flatPackage = false,
          ) -> (Compile / sourceManaged).value,
        ),
        Compile / unmanagedResources += (ThisBuild / baseDirectory).value / "community/ledger-api/VERSION",
        libraryDependencies ++= Seq(
          scalapb_runtime,
          scalapb_runtime_grpc,
          daml_ledger_api_value_scalapb,
        ),
      )
  }

  lazy val `canton-bindings-java` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-bindings-java", file("canton/community/bindings-java"))
      .dependsOn(
        `canton-ledger-api`
      )
      .settings(
        removeTestSources,
        Test / managedSources := Seq(
          (Test / sourceDirectory).value / "scala/com/daml/ledger/javaapi/data/Generators.scala"
        ),
        sharedCantonSettings,
        Test / unmanagedSources :=
          (Test / unmanagedSources).value.filter(_.getName == "Generators.scala"),
        disableTests,
        sharedSettings,
        compileOrder := CompileOrder.JavaThenScala,
        libraryDependencies ++= Seq(
          fasterjackson_core,
          junit_jupiter_api % Test,
          junit_jupiter_engine % Test,
          junit_platform_runner % Test,
          jupiter_interface % Test,
          scalatest,
          scalacheck,
          scalatestScalacheck,
          slf4j_api,
          daml_ledger_api_value_java,
        ),
      )
  }

  lazy val `canton-ledger-json-api` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-ledger-json-api", file("canton/community/ledger/ledger-json-api"))
      .dependsOn(
        `canton-ledger-api-core`,
        `canton-ledger-common` % "test->test",
        `canton-community-testing` % Test,
        `canton-transcode`,
      )
      .disablePlugins(
        ScalafixPlugin,
        ScalafmtPlugin,
        WartRemover,
      ) // to accommodate different daml repo coding style
      .enablePlugins(DamlPlugin)
      .settings(
        sharedCantonSettings,
        removeTestSources,
        sharedSettings,
        scalacOptions --= removeCompileFlagsForDaml :+ "-Wnonunit-statement",
        scalacOptions += "-Wconf:src=src_managed/.*:silent" ++ Seq(
          "lint-byname-implicit",
          "other-match-analysis",
        )
          .map(cat => s"cat=$cat:silent")
          .mkString(",", ",", ""),
        libraryDependencies ++= Seq(
          pekko_http,
          pekko_http_core,
          daml_pekko_http_metrics,
          daml_lf_api_type_signature,
          protostuff_parser,
          tapir_json_circe,
          tapir_pekko_http_server,
          tapir_openapi_docs,
          tapir_asyncapi_docs,
          sttp_apiscpec_openapi_circe_yaml,
          sttp_apiscpec_asyncapi_circe_yaml,
          pekko_stream_testkit % Test,
          scalatest % Test,
          scalacheck % Test,
          scalaz_scalacheck % Test,
          scalatestScalacheck % Test,
          ujson_circe,
          upickle,
        ),
        Test / damlCodeGeneration := Seq(
          (
            (Test / sourceDirectory).value / "daml",
            (Test / damlDarOutput).value / "JsonEncodingTest.dar",
            "com.digitalasset.canton.http.json.encoding",
          )
        ),
      )
  }

  lazy val `canton-transcode` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-transcode", file("canton/community/ledger/transcode/"))
      .settings(
        sharedSettings,
        scalacOptions --= removeCompileFlagsForDaml,
        libraryDependencies ++= Seq(
          daml_lf_language,
          "com.lihaoyi" %% "ujson" % "4.0.2",
        ),
      )
      .dependsOn(`canton-ledger-api`)
  }

  lazy val `canton-sequencer-driver-api` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-sequencer-driver-api", file("canton/community/sequencer-driver"))
      .dependsOn(
        `canton-ledger-api`
      )
      .dependsOn(`canton-util-external`)
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          logback_classic,
          logback_core,
          scala_logging,
          scala_collection_contrib,
          scalatest % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          better_files,
          cats,
          jul_to_slf4j % Test,
          log4j_core,
          log4j_api,
          monocle_macro, // Include it here, even if unused, so that it can be used everywhere
          pureconfig,
        ),
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        // JvmRulesPlugin.damlRepoHeaderSettings,
      )
  }

  lazy val `canton-community-reference-driver` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-reference-driver", file("canton/community/drivers/reference"))
      .dependsOn(
        `canton-util-external`,
        `canton-community-common` % "compile->compile;test->test",
        `canton-sequencer-driver-api` % "compile->compile;test->test",
        `canton-community-testing` % Test,
      )
      .dependsOn(`canton-util-external`)
      .settings(
        sharedCantonSettings,
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf"
        ),
      )
  }

  lazy val `canton-kms-driver-api` = project
    .in(file("canton/community/kms-driver-api"))
    .settings(
      sharedCantonSettings,
      libraryDependencies ++= {
        import CantonDependencies.*
        Seq(
          pureconfig,
          slf4j_api,
          opentelemetry_api,
        )
      },
    )

  import defs._

  /** Typescript code generation from daml models.
    * Generates code for all models given in damlTsCodegenSources into the directory specified in damlTsCodegenDir.
    */
  lazy val damlTsCodegenTask: Def.Initialize[Task[Seq[File]]] = Def.task {
    val log = streams.value.log
    val dars = damlTsCodegenSources.value
    val args: Seq[String] =
      dars.map(_.toString) ++ Seq[String]("-o", damlTsCodegenDir.value.toString)
    val cacheDir = streams.value.cacheDirectory
    val cache =
      FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
        damlTsCodegenDir.value.delete()
        BuildUtil.runCommand("daml2js" +: args, log)
        (baseDirectory.value / "daml.js" ** "*").get.toSet
      }
    cache(dars.toSet).toSeq
  }

  val id = new AtomicInteger(0)

  /** Runs npm-install.sh script, which in turn runs 'npm install' in a dev environment, or
    * 'npm ci' in ci. The source package.json file should be specified in pkg. Rerunning this
    * task will re-execute 'npm install' only if the package file has been modified.
    */
  lazy val npmInstallTask: Def.Initialize[Task[Seq[File]]] = Def.task {
    val pkgs = npmInstallDeps.value
    val openApiPkgs = npmInstallOpenApiDeps.value
    val log = streams.value.log
    val buildDir = (ThisBuild / baseDirectory).value
    val npmInstallScript = buildDir / "build-tools" / "npm-install.sh"
    val cacheDir = streams.value.cacheDirectory / "npmInstall"
    val cache =
      FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
        val i = id.getAndIncrement()
        println(s"Npm install called for ${npmRootDir.value}. Id: $i")
        BuildUtil.runCommandWithRetries(
          Seq(npmInstallScript.getAbsolutePath),
          log,
          None,
          Some(npmRootDir.value),
        )
        println(s"Npm install completed for ${npmRootDir.value}. Id: $i")
        Set(npmRootDir.value / "node_modules")
      }
    val openApiPackageJsons = openApiPkgs.flatMap { case (_, baseDir, hasExternalSpec) =>
      Seq(baseDir / "external-openapi-ts-client" / "package.json").filter(_ => hasExternalSpec) ++
        Seq(
          baseDir / "openapi-ts-client" / "package.json"
        )
    }
    cache(pkgs.toSet ++ openApiPackageJsons).toSeq
  }

  /** Builds frontend code for production by running 'npm run build -w $workspace' for the workspace
    * specified in the frontendWorkspace setting. Will also build the common frontend directory if needed,
    * by calling the task specified in setting commonFrontendBundle.
    */
  lazy val bundleFrontend: Def.Initialize[Task[(File, Set[File])]] = Def.task {
    val commonFrontendFiles = commonFrontendBundle.value
    val log = streams.value.log
    val cacheDir = streams.value.cacheDirectory / "bundleFrontend"
    TS.buildFrontend(
      commonFrontendFiles,
      baseDirectory.value / "../../",
      baseDirectory.value,
      cacheDir,
      frontendWorkspace.value,
      log,
    )
  }

  object TS {
    def buildFrontend(
        commonFrontendFiles: Set[File],
        workingDir: File,
        baseDir: File,
        cacheDir: File,
        workspace: String,
        log: ManagedLogger,
    ): (File, Set[File]) = {
      val sourceFiles =
        (baseDir ** ("*.tsx" || "*.ts" || "*.js" || "*.json") --- baseDir / "build" ** "*" --- baseDir / "node_modules" ** "*").get.toSet
      val cache =
        // We use hashes to detect changes in input files, thus avoid having to deal with file timestamps,
        // and also support cases where we don't cache the entire build pipeline
        FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
          runWorkspaceCommand(workingDir, "build", workspace, log)

          // TODO(#985) - we have to run the type check here because it depends on other things to be built first (e.g. common-frontend, openapi-ts-client, etc). Ideally we run this as part of our static checks instead (npmLint)
          runWorkspaceCommand(workingDir, "type:check", workspace, log)
          val buildFiles = (baseDir / "build" ** "*").get.toSet
          buildFiles
        }
      (baseDir / "build", cache(sourceFiles union commonFrontendFiles))
    }
    def runWorkspaceCommand(
        workingDir: File,
        script: String,
        workspace: String,
        log: ManagedLogger,
    ) = runCommand(
      Seq("npm", "run", script, "--workspace", workspace),
      log,
      None,
      Some(workingDir),
    )
    def openApiSettings(
        unscopedNpmName: String,
        openApiSpec: String,
        directory: String = "openapi-ts-client",
    ): Seq[Setting[_]] = Seq(
      Compile / sourceGenerators +=
        Def.taskDyn {
          val commonInternalOpenApiFile =
            baseDirectory.value / ".." / "common/src/main/openapi/common-internal.yaml"
          val commonExternalOpenApiFile =
            baseDirectory.value / ".." / "common/src/main/openapi/common-external.yaml"

          generateOpenApiClient(
            unscopedNpmName = unscopedNpmName,
            openApiSpec = openApiSpec,
            cacheFileDependencies = Set(commonInternalOpenApiFile, commonExternalOpenApiFile),
            directory = directory,
          )

        },
      cleanFiles += { baseDirectory.value / directory },
    )

    def generateOpenApiClient(
        unscopedNpmName: String,
        openApiSpec: String,
        cacheFileDependencies: Set[File] = Set.empty[File],
        directory: String,
        subPath: String = "src/main/openapi/",
        outputPrefix: Option[String] = None,
    ): Def.Initialize[Task[Seq[File]]] = Def.task {
      val log = streams.value.log
      val cacheDir = streams.value.cacheDirectory / directory

      val npmName = s"@lfdecentralizedtrust/$unscopedNpmName"
      val openApiSpecFile = baseDirectory.value / subPath / openApiSpec
      val template = templateDirectory.value
      val outputDir = outputPrefix.fold(baseDirectory.value)(new java.io.File(_)) / directory
      val cache = FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
        runCommand(
          Seq(
            "openapi-generator-cli",
            "generate",
            "-g",
            "typescript",
            "-t",
            s"$template",
            "-p",
            s"npmName=$npmName",
            "-p",
            s"moduleName=$npmName",
            "-p",
            s"projectName=$npmName",
            "-p",
            "enumPropertyNaming=original",
            "-p",
            "modelPropertyNaming=original",
            "-p",
            "paramNaming=original",
            "-p",
            "useTags=true",
            "-i",
            openApiSpecFile.toString,
            "-o",
            outputDir.getAbsolutePath,
          ),
          log,
        )

        (outputDir.getAbsoluteFile ** "*").get.toSet
      }

      cache(Set(openApiSpecFile) ++ cacheFileDependencies)
      // We need to return an empty Seq here, otherwise SBT tries to compile the typescript files as Scala files.
      Seq.empty[sbt.File]
    }
  }
}
