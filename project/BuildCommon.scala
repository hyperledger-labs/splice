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
    lazy val npmInstallOpenApiDeps =
      taskKey[Seq[(CompileAnalysis, File)]]("Dependencies for npm install task")
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
    // SBT outside of the nix-shell we query nix directly for the PATH.
    val processLogger = new BuildUtil.BufferedLogger
    val exitCode = scala.sys.process
      .Process(
        Seq("nix-build", "-E", "(import nix/default.nix {}).protoc-gen-grpc-web", "--no-out-link"),
        None,
      ) ! processLogger
    val output = processLogger.output()
    if (exitCode != 0) {
      val errorMsg =
        s"Running command returned non-zero exit code: $exitCode $output}"
      throw new IllegalStateException(errorMsg)
    }
    PB.gens.plugin(
      name = "grpc-web",
      path = s"$output/bin/protoc-gen-grpc-web",
    )
  }

  lazy val sharedSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies ++= Seq(
      scalatest % Test,
      Dependencies.daml_bindings_java,
    ),
    // Enable logging of begin and end of test cases, test suites, and test runs.
    Test / testOptions += Tests.Argument("-C", "com.digitalasset.canton.LogReporter"),
  )

  val pbTsDirectory = SettingKey[File]("output directory for ts protobuf definitions")

  lazy val sharedAppSettings: Seq[Def.Setting[_]] =
    sharedSettings ++ cantonWarts ++ protobufLintSettings ++ unusedImportsSetting ++
      Seq(
        pbTsDirectory := baseDirectory.value / "../common/frontend-protobuf",
        Compile / PB.deleteTargetDirectory := false,
        // ^^ do not let protocGenerate delete the entire target directory, otherwise the different apps
        // are deleting each other's outputs. The downside is that for a file name change, or removing a file -
        // we will need to manually clean it first.
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = true) -> (Compile / sourceManaged).value / "protobuf",
          (
            grpcWebGen,
            Seq("mode=grpcwebtext", "import_style=commonjs+dts"),
          ) -> pbTsDirectory.value,
        ),
        Compile / PB.protocOptions ++= Seq(
          s"--js_out=import_style=commonjs:${pbTsDirectory.value}"
        ),
        Compile / PB.protoSources ++= (Test / PB.protoSources).value,
        scalacOptions ++= Seq(
          "-Wconf:src=src_managed/.*:silent",
          "-Wunused:patvars",
          "-Wunused:privates",
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
      Global / excludeLintKeys += `canton-functionmeta` / wartremoverErrors,
      Global / excludeLintKeys += `canton-daml-fork` / wartremoverErrors,
      Global / excludeLintKeys += `canton-blake2b` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-app` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-common` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-domain` / autoAPIMappings,
      Global / excludeLintKeys += `canton-community-participant` / autoAPIMappings,
      //      Global / excludeLintKeys += `demo` / autoAPIMappings,
      Global / excludeLintKeys += `canton-functionmeta` / autoAPIMappings,
      Global / excludeLintKeys += `canton-slick-fork` / autoAPIMappings,
      Global / excludeLintKeys += `canton-daml-fork` / autoAPIMappings,
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
          s"; format ; scalafixAll ; apps-frontends/npmFix ; jsonnetfmtFix",
        ) ++
        addCommandAlias(
          "lint",
          "; protobufLint ; scalafmtCheck ; Test / scalafmtCheck ; scalafmtSbtCheck ; scalafixAll ; apps-frontends/npmLint ; jsonnetfmtCheck",
        ) ++
        // it might happen that some DARs remain dangling on build config changes,
        // so we explicitly remove all CN DARs here, just in case
        addCommandAlias(
          "clean-cn",
          "; apps-common/clean; apps-validator/clean; apps-scan/clean; apps-splitwell/clean; apps-svc/clean; apps-sv/clean; apps-wallet/clean; apps-directory/clean; apps-app/clean; cn-util-daml/clean; canton-coin-daml/clean; canton-coin-api-daml/clean; svc-governance-daml/clean; wallet-daml/clean; wallet-payments-daml/clean; directory-daml/clean; splitwell-daml/clean; apps-frontends/clean; cleanCnDars",
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
        scalacOptions += "-Wconf:cat=unused-imports:is,cat=unused-locals:is,cat=unused-params:is,cat=unused-pat-vars:is,cat=unused-privates:is"
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
          |    - grpc
          |    - google
          |    - scalapb
          |    - com/digitalasset/canton
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
        // Canton depends on the Daml code via a git submodule and the two
        // projects below. We instead depend on the artifacts released
        // from the Daml repo listed in libraryDependencies below.
        // `daml-copy-common`,
        // `daml-copy-testing` % "test->test",
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          daml_error,
          daml_telemetry,
          daml_lf_data,
          daml_lf_nonempty_cats,
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

  lazy val `canton-community-app` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-app", file("canton/community/app"))
      .dependsOn(
        `canton-community-common` % "compile->compile;test->test",
        `canton-community-domain`,
        `canton-community-participant`,
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
          ammonite,
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
  lazy val `canton-community-common` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-community-common", file("canton/community/common"))
      .enablePlugins(BuildInfoPlugin, DamlPlugin)
      .dependsOn(
        `canton-blake2b`,
        `canton-functionmeta`,
        `canton-slick-fork`,
        `canton-wartremover-extension` % "compile->compile;test->test",
        `canton-util-external` % "compile->compile;test->test",
        `canton-util-internal` % "compile->compile;test->test",
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
          daml_metrics,
          daml_error,
          daml_error_generator,
          daml_participant_state, // needed for ReadService/Update classes by PrettyInstances
          daml_ledger_api_common,
          daml_ledger_api_client,
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
          daml_metrics_test_lib % Test,
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
        buildInfoKeys := Seq[BuildInfoKey](
          BuildInfoKey("version", version), // hacked.
          scalaVersion,
          sbtVersion,
          BuildInfoKey("damlLibrariesVersion" -> CantonDependencies.daml_libraries_version),
          BuildInfoKey("vmbc" -> CantonDependencies.daml_libraries_version),
          BuildInfoKey("protocolVersions" -> List("2.0.0", "3.0.0")),
        ),
        buildInfoPackage := "com.digitalasset.canton.buildinfo",
        buildInfoObject := "BuildInfo",
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
      .dependsOn(`canton-community-common` % "compile->compile;test->test", `canton-daml-fork`)
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
          daml_ledger_api_auth_client,
          daml_participant_integration_api,
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
              (Compile / resourceDirectory).value / "dar" / "AdminWorkflows.dar",
              "com.digitalasset.canton.participant.admin.workflows",
            ),
            (
              (Compile / damlDarOutput).value / "AdminWorkflowsWithVacuuming-2.6.0.dar",
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

  lazy val `canton-research-services` = {
    sbt
      .Project("canton-research-services", file("canton/research/app"))
      .disablePlugins(ScalafmtPlugin, WartRemover)
      .settings(
        removeTestSources,
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          daml_ledger_api_scalapb,
          daml_ledger_api_proto % "protobuf",
          CantonDependencies.grpc_services % "protobuf",
          scalapb_runtime_grpc,
          scalapb_runtime,
        ),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage = false) -> (Compile / sourceManaged).value / "protobuf"
        ),
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

  lazy val `canton-functionmeta` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-functionmeta", file("canton/community/lib/functionmeta"))
      .disablePlugins(ScalafmtPlugin, WartRemover)
      .settings(
        removeTestSources,
        sharedSettings,
        libraryDependencies ++= Seq(
          scala_reflect,
          scalatest % Test,
          shapeless % Test,
        ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      // Exclude to apply our license header to any Java files
        //      headerSources / excludeFilter := "*.java",
        //      coverageEnabled := false,
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
      .apply("canton-akka-fork", file("community/lib/akka"))
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

  lazy val `canton-daml-fork` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-fork", file("canton/community/lib/daml"))
      .disablePlugins(WartRemover) // to accommodate different daml repo coding style
      .settings(
        removeTestSources,
        sharedSettings,
        libraryDependencies ++= Seq(),
        dependencyOverrides ++= Seq(log4j_core, log4j_api),
        Compile / PB.targets := Seq(
          scalapb.gen(flatPackage =
            false // consistent with upstream daml
          ) -> (Compile / sourceManaged).value / "protobuf"
        ),
        // commented out from Canton OS repo as settings don't apply to us (yet)
        //      coverageEnabled := false,
        //      JvmRulesPlugin.damlRepoHeaderSettings,
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
        BuildUtil.runCommand("daml2ts" +: args, log)
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
    val cacheDir = streams.value.cacheDirectory
    val cache =
      FileFunction.cached(cacheDir) { _ =>
        BuildUtil.runCommandWithRetries(
          Seq(npmInstallScript.getAbsolutePath),
          log,
          None,
          Some(npmRootDir.value),
        )
        Set(npmRootDir.value / "node_modules")
      }
    val openApiPackageJsons = openApiPkgs.map { case (_, baseDir) =>
      baseDir / "openapi-ts-client" / "package.json"
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
    val cacheDir = streams.value.cacheDirectory
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
        FileFunction.cached(cacheDir) { _ =>
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
    def openApiSettings(npmName: String, openApiSpec: String): Seq[Setting[_]] = Seq(
      Compile / sourceGenerators +=
        Def.taskDyn {
          val commonOpenApiFile = baseDirectory.value / ".." / "common/src/main/openapi/common.yaml"

          generateOpenApiClient(
            npmName = npmName,
            npmModuleName = npmName,
            npmProjectName = npmName,
            openApiSpec = openApiSpec,
            cacheFileDependencies = Set(commonOpenApiFile),
          )
        }
    )

    def generateOpenApiClient(
        npmName: String,
        npmModuleName: String,
        npmProjectName: String,
        openApiSpec: String,
        cacheFileDependencies: Set[File] = Set.empty[File],
    ): Def.Initialize[Task[Seq[File]]] = Def.task {
      import better.files.*
      import _root_.io.circe.*
      import _root_.io.circe.parser.*
      import _root_.io.circe.optics.JsonPath.*
      import _root_.io.circe.optics.JsonPath.{root => jsonRoot}
      import _root_.io.circe.syntax._

      val log = streams.value.log
      val cacheDir = streams.value.cacheDirectory

      val openApiSpecFile = baseDirectory.value / "src/main/openapi/" / openApiSpec
      val cache = FileFunction.cached(cacheDir) { _ =>
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
            (baseDirectory.value / "openapi-ts-client").getAbsolutePath,
          ),
          log,
        )

        // Add empty check task to make npm happy
        val packageJson =
          File((baseDirectory.value / "openapi-ts-client" / "package.json").toString)

        val packageJsonContent = packageJson.contentAsString
        val doc: Json =
          parse(packageJsonContent).getOrElse(sys.error("Failed to parse package.json"))
        val updated = jsonRoot.scripts.obj.modify((obj: JsonObject) =>
          obj.add("check", s"echo '[${npmProjectName}] no-op'".asJson)
        )(doc)
        packageJson.overwrite(updated.spaces2)

        ((baseDirectory.value ** "*") --- ((baseDirectory.value / "target" +++ baseDirectory.value / "dist") ** "*")).get.toSet
      }

      cache(Set(openApiSpecFile) ++ cacheFileDependencies)
      // We need to return an empty Seq here, otherwise SBT tries to compile the typescript files as Scala files.
      Seq.empty[sbt.File]
    }
  }
}
