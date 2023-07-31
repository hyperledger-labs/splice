import BuildUtil.runCommand
import scalafix.sbt.ScalafixPlugin
import sbt.Keys._
import sbt._
import Dependencies._
import org.scalafmt.sbt.ScalafmtPlugin
import sbt.nio.Keys._
import sbtbuildinfo.BuildInfoPlugin
import sbtbuildinfo.BuildInfoPlugin.autoImport._
import wartremover.WartRemover
import wartremover.WartRemover.autoImport._
import sbtprotoc.ProtocPlugin.autoImport.PB
import DamlPlugin.autoImport._
import sbt.internal.util.ManagedLogger
import xsbti.compile.CompileAnalysis

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

    lazy val compileOpenApi = taskKey[Seq[File]]("build typescript code")
    lazy val frontendWorkspace = settingKey[String]("npm workspace to bundle")
    lazy val commonFrontendBundle =
      taskKey[Set[File]]("common frontend bundle task to run before the app frontend bundle")
  }

  val grpcWebGen = {
    // While the error claims that being in PATH is sufficient, the error is lying. It really
    // needs to be an absolute path. Since some people also like starting
    // SBT outside of the nix develop we query nix directly for the PATH.
    val processLogger = new BuildUtil.BufferedLogger
    val exitCode = scala.sys.process
      .Process(
        Seq("nix", "build", "path:nix#protoc-gen-grpc-web", "--no-link", "--print-out-paths"),
        None,
      ) ! processLogger
    if (exitCode != 0) {
      val errorMsg =
        s"Running command returned non-zero exit code: $exitCode ${processLogger.output()}}"
      throw new IllegalStateException(errorMsg)
    }
    PB.gens.plugin(
      name = "grpc-web",
      path = s"${processLogger.outputStdout()}/bin/protoc-gen-grpc-web",
    )
  }

  lazy val sharedSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies ++= Seq(
      scalatest % Test,
      Dependencies.daml_bindings_java,
    ),
    resolvers += "Artifactory Canton Drivers" at "https://digitalasset.jfrog.io/artifactory/canton-drivers/",
    credentials += Credentials(
      "Artifactory Realm",
      "digitalasset.jfrog.io",
      sys.env("ARTIFACTORY_USER"),
      sys.env("ARTIFACTORY_PASSWORD"),
    ),
    // Enable logging of begin and end of test cases, test suites, and test runs.
    Test / testOptions += Tests.Argument("-C", "com.digitalasset.canton.LogReporter"),
  )

  val pbTsDirectory = SettingKey[File]("output directory for ts protobuf definitions")

  lazy val sharedAppSettings: Seq[Def.Setting[_]] =
    sharedSettings ++ cantonWarts ++ protobufLintSettings ++ unusedImportsSetting ++
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
          "-Wunused:patvars",
          "-Wunused:privates",
          "-Wunused:params",
        ),
      )

  lazy val damlSettings: Seq[Def.Setting[_]] =
    BuildCommon.sharedAppSettings ++
      BuildCommon.copyDarResources ++
      Seq(
        Compile / damlCodeGeneration := {
          val Seq(darFile) = (Compile / damlBuild).value
          Seq(
            (
              (Compile / baseDirectory).value,
              darFile,
              "com.daml.network.codegen",
            )
          )
        }
      )

  lazy val copyDarResources: Seq[Def.Setting[_]] = {
    Seq(
      Compile / resourceGenerators += Def.task {
        val Seq(srcFile) = (Compile / damlBuild).value
        val dstFile = (Compile / resourceDirectory).value / "dar" / srcFile.getName()
        IO.copyFile(srcFile, dstFile)
        Seq(dstFile)
      }.taskValue,
      cleanFiles += {
        (Compile / resourceDirectory).value / "dar"
      },
    )
  }

  lazy val removeCompileFlagsForDaml =
    Seq("-Xsource:3", "-deprecation", "-Xfatal-warnings", "-Ywarn-unused", "-Ywarn-value-discard")

  lazy val sbtSettings: Seq[Def.Setting[_]] = {

    def alsoTest(taskName: String) = s";$taskName; Test / $taskName"

    val globalSettings = Seq(
      name := "coin",
      // Automatically reload sbt project when sbt build definition files change
      Global / onChangedBuildSource := ReloadOnSourceChanges,
      // allow setting number of tasks via environment
      Global / concurrentRestrictions ++= sys.env
        .get("MAX_CONCURRENT_SBT_TEST_TASKS")
        .map(_.toInt)
        .map(Tags.limit(Tags.Test, _))
        .toSeq,
      // copied from the Canton OSS repo
      Global / excludeLintKeys += Compile / damlBuildOrder,
      Global / excludeLintKeys += `canton-blake2b` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-app` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-common` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-domain` / autoAPIMappings,
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
          s"; format ; scalafixAll ; apps-frontends/npmFix ; pulumi/npmFix",
        ) ++
        addCommandAlias(
          "lint",
          "; damlDarsLockFileCheck ; protobufLint ; scalafmtCheck ; Test / scalafmtCheck ; scalafmtSbtCheck ; scalafixAll ; apps-frontends/npmLint ; pulumi/npmLint ; runShellcheck ; syncpackCheck",
        ) ++
        // it might happen that some DARs remain dangling on build config changes,
        // so we explicitly remove all CN DARs here, just in case
        addCommandAlias(
          "clean-cn",
          "; apps-common/clean; apps-validator/clean; apps-scan/clean; apps-splitwell/clean; apps-sv/clean; apps-wallet/clean; apps-directory/clean; apps-app/clean; cn-util-daml/clean; canton-coin-daml/clean; canton-coin-api-daml/clean; svc-governance-daml/clean; wallet-daml/clean; wallet-payments-daml/clean; directory-daml/clean; splitwell-daml/clean; validator-lifecycle-daml/clean; canton-coin-v1test-daml/clean; canton-coin-v2test-daml/clean; svc-governance-v1test-daml/clean; wallet-v1test-daml/clean; apps-frontends/clean; cleanCnDars",
        ) ++
        addCommandAlias("cn-clean", "; clean-cn")
    val buildSettings = inThisBuild(
      Seq(
        organization := "com.daml.network",
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
    wartremoverErrors += Wart.custom("com.digitalasset.canton.SlickString"),
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

  lazy val protobufLint = taskKey[Unit](
    "Lint protobuf sources using the `buf` tool."
  )

  val protobufLintSettings = List(
    Compile / PB.includePaths += file("3rdparty/protobuf"),
    protobufLint := {
      val targetSourceDir = target.value / "protobuf_merged_sources"
      val includeDirs = (Compile / PB.includePaths).value
      // trigger PB.generate to ensure includeDirs are populated with external deps
      val _ = (Compile / PB.generate).value
      // delete source dir first to avoid problems with renamed files lingering around
      IO.delete(targetSourceDir)
      // merge all sources *assuming* proto paths are unique, which
      // should hold as otherwise `protoc` will complain during dependency resolution
      includeDirs.foreach(includeDir =>
        IO.copyDirectory(
          includeDir,
          targetSourceDir,
          CopyOptions(overwrite = true, preserveLastModified = true, preserveExecutable = false),
        )
      )
      // create buf file reflecting our policy
      IO.write(
        targetSourceDir / "buf.yaml",
        """
          |version: v1
          |lint:
          |  use:
          |    # Using DEFAULT as ...
          |    - DEFAULT
          |    - PACKAGE_NO_IMPORT_CYCLE
          |    # Disallow as we want to consciously decide whether we want to use streaming endpoints.
          |    # We'll probably use server-side streaming, but not client-side streaming as it is not
          |    # supported by grpc-web.
          |    - RPC_NO_CLIENT_STREAMING
          |    - RPC_NO_SERVER_STREAMING
          |  rpc_allow_google_protobuf_empty_requests: true
          |  rpc_allow_google_protobuf_empty_responses: true
          |  except:
          |    # TODO(tech-debt): enable this by changing our v0 prefix to v1
          |    - PACKAGE_VERSION_SUFFIX
          |  ignore:
          |    # Ignoring proto packages with these prefixes as they are external dependencies
          |    - com/daml/ledger/api/v1
          |    - com/daml/ledger/api/v2
          |    - grpc
          |    - google
          |    - scalapb
          |    - com/digitalasset/canton
          |    - daml/platform
          |    # TODO(tech-debt): ignore also the non-external project dependencies
          |""".stripMargin,
      )
      // call buf tool
      BuildUtil.runCommand(Seq("buf", "lint"), streams.value.log, optCwd = Some(targetSourceDir))
      ()
    },
  )

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
    scalacOptions += "-Wconf:src=src_managed/.*:silent",
  )

  // Project for utilities that are also used outside of the Canton repo
  lazy val `canton-util-external` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-util-external", file("canton/community/util-external"))
      .dependsOn(
        `canton-akka-fork`,
        `canton-wartremover-extension` % "compile->compile;test->test",
        `canton-ledger-common`,
        `canton-util-logging`,
        // Canton depends on the Daml code via a git submodule and the two
        // projects below. We instead depend on the artifacts released
        // from the Daml repo listed in libraryDependencies below.
        // `daml-copy-common`,
        // `daml-copy-testing` % "test->test",
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          daml_telemetry,
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
          opentelemetry_jaeger,
        ),
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        // JvmRulesPlugin.damlRepoHeaderSettings,
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

  lazy val `canton-util-logging` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-util-logging", file("canton/community/util-logging"))
      .dependsOn(
        `canton-daml-errors`,
        `canton-wartremover-extension` % "compile->compile;test->test",
      )
      .settings(
        sharedSettings ++ cantonWarts,
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        libraryDependencies ++= Seq(
          daml_grpc_utils,
          daml_lf_data,
          daml_metrics,
          daml_telemetry,
          daml_nonempty_cats,
          daml_tracing,
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
          opentelemetry_jaeger,
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
        `canton-community-domain`,
        `canton-community-participant`,
        `canton-community-integration-testing` % "test",
      )
      .enablePlugins(DamlPlugin)
      .settings(
        // commented out from Canton OS repo as settings don't apply to us
        //      sharedAppSettings,
        disableTests,
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
          akka_stream_testkit % Test,
          akka_http,
          akka_http_testkit % Test,
          pureconfig_cats,
          cats,
          daml_ledger_rxjava_client % Test,
          better_files,
          toxiproxy_java % Test,
          dropwizard_metrics_jvm, // not used at compile time, but required at runtime to report jvm metrics
          dropwizard_metrics_jmx,
          dropwizard_metrics_graphite,
        ),
        // commented out from Canton OS repo as settings because they don't apply to us
        // core packaging commands
        //      bundlePack := sharedAppPack,
        //      additionalBundleSources := Seq.empty,
        //      assembly / mainClass := Some("com.digitalasset.canton.CantonCommunityApp"),
        //      assembly / assemblyJarName := s"canton-open-source-${version.value}.jar",
        // specifying the damlSourceDirectory to non-default location enables checking/updating of daml version
        Compile / damlSourceDirectory := sourceDirectory.value / "pack" / "examples" / "06-messaging",
        // clearing the damlBuild tasks to prevent compiling which does not work due to relative file "data-dependencies";
        // "data-dependencies" daml.yaml setting relies on hardcoded "0.0.1" project version
        Compile / damlBuild := Seq(), // message-0.0.1.dar is hardcoded and contact-0.0.1.dar is built by MessagingExampleIntegrationTest
        Test / damlBuild := Seq(),
        Test / damlTest := Seq(),
        Compile / damlProjectVersionOverride := Some("0.0.1"),
        Compile / damlEnableScalaCodegen := true,
        Compile / damlEnableJavaCodegen := false,
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
        `canton-community-domain`,
        `canton-community-participant`,
      )
      .settings(
        removeTestSources,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          akka_http,
          ammonite,
          dropwizard_metrics_jmx,
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
        `canton-ledger-common`,
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
          grpc_services,
          postgres,
          pprint,
          scaffeine,
          daml_nonempty_cats,
          daml_lf_value_java_proto % "protobuf", // needed for protobuf import
          daml_lf_transaction,
          CantonDependencies.grpc_services % "protobuf",
          scalapb_runtime_grpc,
          scalapb_runtime,
          slick_hikaricp,
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
          BuildInfoKey("protocolVersions" -> List("3", "4")),
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
      )
      .settings(
        sharedCantonSettings,

        // The dependency override is needed because `community-testing` depends transitively on
        // `scalatest` and `community-app-base` depends transitively on `ammonite`, which in turn
        // depend on incompatible versions of `scala-xml` -- not ideal but only causes possible
        // runtime errors while testing and none have been found so far, so this should be fine for now
        dependencyOverrides += "org.scala-lang.modules" %% "scala-xml" % "2.0.1",

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
        `canton-akka-fork` % "compile->compile;test->test",
        `canton-community-base`,
        `canton-slick-fork`,
        `canton-wartremover-extension` % "compile->compile;test->test",
        `canton-util-external` % "compile->compile;test->test",
        `canton-util-internal` % "compile->compile;test->test",
        `canton-community-testing` % "test",
        `canton-ledger-common` % "compile->compile;test->test",
      )
      .settings(
        disableTests,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          akka_slf4j, // not used at compile time, but required by com.digitalasset.canton.util.AkkaUtil.createActorSystem
          daml_lf_archive_reader,
          daml_lf_engine,
          daml_lf_value_java_proto % "protobuf", // needed for protobuf import
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
          grpc_netty,
          grpc_services,
          scalapb_runtime_grpc,
          scalapb_runtime,
          log4j_core,
          log4j_api,
          flyway excludeAll (ExclusionRule("org.apache.logging.log4j")),
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
          dropwizard_metrics_core,
          prometheus_dropwizard, // Include it here to overwrite transitive dependencies by DAML libraries
          prometheus_httpserver, // Include it here to overwrite transitive dependencies by DAML libraries
          prometheus_hotspot, // Include it here to overwrite transitive dependencies by DAML libraries
          opentelemetry_api,
          opentelemetry_sdk,
          opentelemetry_sdk_autoconfigure,
          opentelemetry_instrumentation_grpc,
          opentelemetry_zipkin,
          opentelemetry_jaeger,
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
          val Seq(darFile) = (Compile / damlBuild).value
          Seq(
            (
              (Compile / baseDirectory).value,
              darFile,
              "com.digitalasset.canton.examples",
            )
          )
        },
        Test / damlTest := Seq(),
        Compile / damlEnableScalaCodegen := true,
        Compile / damlEnableJavaCodegen := false,
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //    addProtobufFilesToHeaderCheck(Compile),
        //    addFilesToHeaderCheck("*.daml", "daml", Compile),
        //    JvmRulesPlugin.damlRepoHeaderSettings
      )
  }

  lazy val `canton-community-domain` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-domain", file("canton/community/domain"))
      .dependsOn(`canton-community-common` % "compile->compile;test->test")
      .settings(
        removeTestSources,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
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

  lazy val `canton-community-participant` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-participant", file("canton/community/participant"))
      .dependsOn(
        `canton-community-common` % "compile->compile;test->test",
        `canton-ledger-api-core` % "compile->compile;test->test",
        `canton-ledger-json-api`,
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
          daml_lf_dev_archive_java_proto,
          daml_lf_engine,
          daml_ledger_api_proto % "protobuf",
          logback_classic % Runtime,
          logback_core % Runtime,
          akka_stream,
          akka_stream_testkit % Test,
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
              (Compile / baseDirectory).value,
              (Compile / resourceDirectory).value / "dar" / "AdminWorkflows.dar",
              "com.digitalasset.canton.participant.admin.workflows",
            ),
            (
              (Compile / baseDirectory).value,
              (Compile / damlDarOutput).value / "AdminWorkflowsWithVacuuming-2.7.0.dar",
              "com.digitalasset.canton.participant.admin.workflows",
            ),
          ),
        Compile / damlEnableScalaCodegen := true,
        Compile / damlEnableJavaCodegen := false,
        damlFixedDars := Seq("AdminWorkflows.dar"),
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
      .dependsOn(`canton-slick-fork`)
      .settings(
        disableTests,
        sharedSettings,
        libraryDependencies ++= Seq(
          cats,
          mockito_scala % Test,
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

  // https://github.com/DACH-NY/canton/issues/10617: remove when no longer needed
  lazy val `canton-akka-fork` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-akka-fork", file("canton/community/lib/akka"))
      .disablePlugins(ScalafixPlugin, ScalafmtPlugin, WartRemover)
      .settings(
        sharedSettings,
        libraryDependencies ++= Seq(
          akka_stream,
          akka_stream_testkit % Test,
          akka_slf4j,
          scalatest % Test,
        ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      // Exclude to apply our license header to any Scala files
        //      headerSources / excludeFilter := "*.scala",
        //      coverageEnabled := false,
      )
  }

  lazy val `canton-daml-errors` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-errors", file("canton/daml-common-staging/daml-errors"))
      .dependsOn(
        `canton-wartremover-extension` % "compile->compile;test->test"
      )
      .settings(
        sharedSettings ++ cantonWarts,
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        libraryDependencies ++= Seq(
          slf4j_api,
          grpc_api,
          reflections,
          scalatest % Test,
          daml_ledger_api_scalapb,
        ),
      )
  }

  lazy val `canton-ledger-common` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-ledger-common", file("canton/community/ledger/ledger-common"))
      .disablePlugins(WartRemover, ScalafmtPlugin)
      .dependsOn(
        `canton-daml-errors` % "compile->compile;test->test"
      )
      .settings(
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
          daml_grpc_utils,
          daml_lf_engine,
          daml_lf_archive_reader,
          daml_tracing,
          apache_commons_codec,
          apache_commons_io,
          daml_bindings_scala,
          daml_ledger_resources,
          daml_timer_utils,
          daml_rs_grpc_akka,
          dropwizard_metrics_core,
          opentelemetry_api,
          akka_stream,
          slf4j_api,
          grpc_api,
          reflections,
          grpc_netty,
          netty_boring_ssl, // This should be a Runtime dep, but needs to be declared at Compile scope due to https://github.com/sbt/sbt/issues/5568
          netty_handler,
          caffeine,
          scalapb_runtime,
          scalapb_runtime_grpc,
          awaitility % Test,
          logback_classic % Test,
          scalatest % Test,
          mockito_scala % Test,
          scalatestMockito % Test,
          akka_stream_testkit % Test,
          scalacheck % Test,
          opentelemetry_sdk_testing % Test,
          scalatestScalacheck % Test,
          daml_metrics,
          daml_lf_data,
          daml_lf_transaction,
          daml_test_common % Test,
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
        `canton-ledger-common` % "compile->compile;test->test",
        `canton-community-base`,
      )
      .disablePlugins(
        WartRemover,
        ScalafmtPlugin,
      ) // to accommodate different daml repo coding style
      .settings(
        removeTestSources,
        sharedSettings,
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = false) -> (Compile / sourceManaged).value / "protobuf"
        ),
        libraryDependencies ++= Seq(
          auth0_java,
          auth0_jwks,
          circe_core,
          daml_jwt,
          daml_ports,
          daml_struct_spray_json,
          netty_boring_ssl,
          netty_handler,
          hikaricp,
          guava,
          dropwizard_metrics_core,
          bouncycastle_bcprov_jdk15on % Test,
          bouncycastle_bcpkix_jdk15on % Test,
          scalaz_scalacheck % Test,
          grpc_netty,
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
          testcontainers % Test,
          testcontainers_postgresql % Test,
        ),
        Test / parallelExecution := true,
        Test / fork := false,
      )
  }

  lazy val `canton-ledger-json-api` = {
    import CantonDependencies.*
    sbt.Project
      .apply("canton-ledger-json-api", file("canton/community/ledger/ledger-json-api"))
      .dependsOn(
        `canton-ledger-api-core`,
        `canton-ledger-common` % "test->test",
        `canton-community-testing` % Test,
      )
      .disablePlugins(
        ScalafixPlugin,
        ScalafmtPlugin,
        WartRemover,
      ) // to accommodate different daml repo coding style
      .enablePlugins(DamlPlugin)
      .settings(
        removeTestSources,
        sharedSettings,
        scalacOptions --= removeCompileFlagsForDaml
          // needed for foo.bar.{this as that} imports
          .filterNot(_ == "-Xsource:3"),
        scalacOptions += "-Wconf:src=src_managed/.*:silent",
        libraryDependencies ++= Seq(
          akka_http,
          akka_http_core,
          daml_lf_api_type_signature,
          spray_json_derived_codecs,
          akka_stream_testkit % Test,
          scalatest % Test,
          scalacheck % Test,
          scalaz_scalacheck % Test,
          scalatestScalacheck % Test,
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
      FileFunction.cached(cacheDir) { _ =>
        damlTsCodegenDir.value.delete()
        BuildUtil.runCommand("daml2js" +: args, log)
        (baseDirectory.value / "daml.js" ** "*").get.toSet
      }
    cache(dars.toSet).toSeq
  }

  /** Runs npm-install.sh script, which in turn runs 'npm install' in a dev environment, or
    * 'npm ci' in ci. The source package.json file should be specified in pkg. Rerunning this
    * task will re-execute 'npm install' only if the package file has been modified.
    */
  lazy val npmInstallTask: Def.Initialize[Task[Seq[File]]] = Def.task {
    val pkgs = npmInstallDeps.value
    val openApiPkgs = npmInstallOpenApiDeps.value
    val log = streams.value.log
    val npmInstallScript = npmRootDir.value / "../build-tools/npm-install.sh"
    val cacheDir = streams.value.cacheDirectory / "npmInstall"
    val cache =
      FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
        BuildUtil.runCommandWithRetries(
          Seq(npmInstallScript.getAbsolutePath),
          log,
          None,
          Some(npmRootDir.value),
        )
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
          runBuildCommand(workingDir, workspace, log)
          val buildFiles = (baseDir / "build" ** "*").get.toSet
          buildFiles
        }
      (baseDir / "build", cache(sourceFiles union commonFrontendFiles))
    }
    def runBuildCommand(workingDir: File, workspace: String, log: ManagedLogger) = runCommand(
      Seq("npm", "run", "build", "--workspace", workspace),
      log,
      None,
      Some(workingDir),
    )
    def openApiSettings(
        npmName: String,
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
            npmName = npmName,
            npmModuleName = npmName,
            npmProjectName = npmName,
            openApiSpec = openApiSpec,
            cacheFileDependencies = Set(commonInternalOpenApiFile, commonExternalOpenApiFile),
            directory = directory,
          )
        }
    )

    def generateOpenApiClient(
        npmName: String,
        npmModuleName: String,
        npmProjectName: String,
        openApiSpec: String,
        cacheFileDependencies: Set[File] = Set.empty[File],
        directory: String,
    ): Def.Initialize[Task[Seq[File]]] = Def.task {
      import better.files._
      import _root_.io.circe._
      import _root_.io.circe.parser._

      val log = streams.value.log
      val cacheDir = streams.value.cacheDirectory / directory

      val openApiSpecFile = baseDirectory.value / "src/main/openapi/" / openApiSpec
      val cache = FileFunction.cached(cacheDir, FileInfo.hash) { _ =>
        runCommand(
          Seq(
            "openapi-generator-cli",
            "generate",
            "-g",
            "typescript",
            "-p",
            s"npmName=${npmName}",
            "-p",
            s"moduleName=${npmModuleName}",
            "-p",
            s"projectName=${npmProjectName}",
            "-p",
            "useTags=true",
            "-i",
            openApiSpecFile.toString,
            "-o",
            (baseDirectory.value / directory).getAbsolutePath,
          ),
          log,
        )

        // Add empty check task to make npm happy
        val packageJson =
          File((baseDirectory.value / directory / "package.json").toString)

        val packageJsonContent = packageJson.contentAsString
        val doc: Json =
          parse(packageJsonContent).getOrElse(sys.error("Failed to parse package.json"))

        (baseDirectory.value / directory ** "*").get.toSet
      }

      cache(Set(openApiSpecFile) ++ cacheFileDependencies)
      // We need to return an empty Seq here, otherwise SBT tries to compile the typescript files as Scala files.
      Seq.empty[sbt.File]
    }
  }
}
