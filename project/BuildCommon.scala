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

object BuildCommon {

  lazy val sharedSettings: Seq[Def.Setting[_]] = Seq(
    libraryDependencies += scalatest % Test
  )

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
      // cheekily overwriting test task here because we don't want the tests of the copied Canton files to run
      // additionally, we don't want LiveDevNetTest tests to run by default either
      addCommandAlias("test", "; apps-app/testOnly * -- -l LiveDevNetTest") ++
        addCommandAlias("testOnly", "; apps-app/testOnly") ++
        addCommandAlias(
          "scalafixCheck",
          s"${alsoTest("scalafix --check")}",
        ) ++ addCommandAlias(
          "format",
          s"; scalafmt ; Test / scalafmt ; scalafmtSbt",
        )

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

  /** Utility function to run a (shell) command. */
  def runCommand(command: String, log: ManagedLogger, optError: Option[String] = None): String = {
    import scala.sys.process.Process
    val processLogger = new DamlPlugin.BufferedLogger
    log.debug(s"Running ${command}")
    val exitCode = Process(command) ! processLogger
    val output = processLogger.output()
    if (exitCode != 0) {
      val errorMsg = s"A problem occurred when executing command `$command` in `build.sbt`: ${System
        .lineSeparator()} $output"
      log.error(errorMsg)
      if (optError.isDefined) log.error(optError.getOrElse(""))
      throw new IllegalStateException(errorMsg)
    }
    if (output != "") log.info(processLogger.output())
    output
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
        Compile / damlProjectVersionOverride := Some("0.0.1"),
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
        `canton-wartremover-extension`,
      )
      .settings(
        sharedCantonSettings,
        libraryDependencies ++= Seq(
          akka_slf4j, // not used at compile time, but required by com.digitalasset.canton.util.AkkaUtil.createActorSystem
          daml_lf_archive_reader,
          daml_lf_engine,
          daml_lf_value_java_proto % "protobuf", // needed for protobuf import
          daml_lf_transaction, //needed for importing java classes
          daml_metrics,
          daml_error,
          daml_error_generator,
          daml_participant_state, //needed for ReadService/Update classes by PrettyInstances
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
        Compile / damlCodeGeneration := Seq(
          (
            (Compile / sourceDirectory).value / "daml" / "CantonExamples",
            (Compile / damlDarOutput).value / "CantonExamples.dar",
            "com.digitalasset.canton.examples",
          )
        ),
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
        Compile / damlCodeGeneration := Seq(
          (
            (Compile / sourceDirectory).value / "daml",
            (Compile / resourceDirectory).value / "dar" / "AdminWorkflows.dar",
            "com.digitalasset.canton.participant.admin.workflows",
          )
        ),
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
        sharedSettings,
        libraryDependencies ++= Seq(
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

  lazy val `canton-daml-fork` = {
    import CantonDependencies._
    sbt.Project
      .apply("canton-daml-fork", file("canton/community/lib/daml"))
      .disablePlugins(WartRemover) // to accommodate different daml repo coding style
      .settings(
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

}
