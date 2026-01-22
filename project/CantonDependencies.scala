// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import sbt._

/** Copied from Canton OSS repo. */
object CantonDependencies {
  // Slightly changed compared to Canton OSS repo to avoid the need for a meta sbt project
  val version: String = "3.4.4"
  val daml_language_versions = Seq("2.1")
  val daml_libraries_version = version
  // Defined in `./daml-compiler-sources.json`, as the compiler version is also used by
  // the non-sbt based docker build.
  val daml_compiler_version = sys.env("DAML_COMPILER_VERSION")
  val daml_java_codegen_version = version
  val use_custom_daml_version = false

  lazy val osClassifier: String =
    if (sys.props("os.name").contains("Mac")) "osx"
    else sys.props("os.name").toLowerCase

  lazy val scala_version = "2.13.16"
  lazy val scala_version_short = "2.13"

  lazy val anorm = "org.playframework.anorm" %% "anorm" % "2.7.0"
  lazy val apispec_version = "0.11.7"
  lazy val pekko_version = "1.2.0"
  lazy val pekko_http_version = "1.2.0"
  lazy val auth0_java = "com.auth0" % "java-jwt" % "4.2.1"
  lazy val auth0_jwks = "com.auth0" % "jwks-rsa" % "0.21.2"
  lazy val awaitility = "org.awaitility" % "awaitility" % "4.2.0"
  lazy val grpc_version = "1.75.0"
  lazy val logback_version = "1.5.3"
  lazy val slf4j_version = "2.0.6"
  lazy val log4j_version = "2.17.0"
  lazy val ammonite_version = "3.0.1"
  lazy val pprint_version = "0.7.1"
  // if you update the slick version, please also update our forked code in common/slick.util.*
  lazy val slick_version = "3.5.2"
  lazy val bouncy_castle_version = "1.79"

  lazy val pureconfig_version = "0.14.0"

  lazy val circe_version = "0.14.2"

  lazy val scalatest_version = "3.2.19"
  lazy val scalacheck_version = "1.15.4"
  lazy val scalaz_version = "7.2.33"
  lazy val mockito_scala_version = "1.16.3"

  lazy val netty_version = "4.2.9.Final"

  lazy val reflections = "org.reflections" % "reflections" % "0.10.2"
  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % pureconfig_version
  lazy val pureconfig_cats = "com.github.pureconfig" %% "pureconfig-cats" % pureconfig_version

  lazy val scala_collection_contrib =
    "org.scala-lang.modules" %% "scala-collection-contrib" % "0.3.0"
  lazy val scala_reflect = "org.scala-lang" % "scala-reflect" % scala_version
  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.12"

  lazy val monocle_version = "3.2.0"
  lazy val monocle_core = "dev.optics" %% "monocle-core" % monocle_version
  lazy val monocle_macro = "dev.optics" %% "monocle-macro" % monocle_version

  // ammonite requires the full scala version including patch number
  lazy val ammonite = "com.lihaoyi" % "ammonite" % ammonite_version cross CrossVersion.full
  lazy val pprint = "com.lihaoyi" %% "pprint" % pprint_version

  lazy val hikaricp = "com.zaxxer" % "HikariCP" % "3.2.0"
  lazy val h2 = "com.h2database" % "h2" % "2.1.210"
  lazy val postgres = "org.postgresql" % "postgresql" % "42.7.3"
  private val flyway_version = "10.22.0"
  lazy val flyway = "org.flywaydb" % "flyway-core" % flyway_version
  lazy val flyway_postgresql = "org.flywaydb" % "flyway-database-postgresql" % flyway_version
  lazy val oracle = "com.oracle.database.jdbc" % "ojdbc8" % "19.13.0.0.1"

  // Picked up automatically by the scalapb compiler. Contains common dependencies such as protocol buffers like google/protobuf/timestamp.proto
  lazy val scalapb_runtime =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  lazy val scalapb_runtime_grpc =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion

  lazy val daml_test_evidence_tag =
    "com.daml" %% "test-evidence-tag" % daml_libraries_version
  lazy val daml_test_evidence_scalatest =
    "com.daml" %% "test-evidence-scalatest" % daml_libraries_version
  lazy val daml_test_evidence_generator_scalatest =
    "com.daml" %% "test-evidence-generator" % daml_libraries_version
  lazy val daml_lf_archive_reader = "com.daml" %% "daml-lf-archive-reader" % daml_libraries_version
  lazy val daml_lf_data = "com.daml" %% "daml-lf-data" % daml_libraries_version
  lazy val daml_lf_engine = "com.daml" %% "daml-lf-engine" % daml_libraries_version
  lazy val daml_lf_language = "com.daml" %% "daml-lf-language" % daml_libraries_version
  lazy val daml_lf_transaction = "com.daml" %% "daml-lf-transaction" % daml_libraries_version
  lazy val daml_lf_transaction_test_lib =
    "com.daml" %% "daml-lf-transaction-test-lib" % daml_libraries_version
  lazy val daml_lf_api_type_signature =
    "com.daml" %% "daml-lf-api-type-signature" % daml_libraries_version
  lazy val daml_libs_scala_grpc_test_utils =
    "com.daml" %% "grpc-test-utils" % daml_libraries_version

  lazy val daml_nonempty = "com.daml" %% "nonempty" % daml_libraries_version
  lazy val daml_nonempty_cats = "com.daml" %% "nonempty-cats" % daml_libraries_version
  lazy val daml_metrics_test_lib = "com.daml" %% "metrics-test-lib" % daml_libraries_version
  lazy val daml_contextualized_logging =
    "com.daml" %% "contextualized-logging" % daml_libraries_version
  lazy val daml_metrics = "com.daml" %% "metrics" % daml_libraries_version
  lazy val daml_pekko_http_metrics = "com.daml" %% "pekko-http-metrics" % daml_libraries_version
  lazy val daml_tracing = "com.daml" %% "tracing" % daml_libraries_version
  lazy val daml_tracing_test_lib = "com.daml" %% "tracing-test-lib" % daml_libraries_version
  lazy val daml_executors = "com.daml" %% "executors" % daml_libraries_version
  lazy val daml_ports = "com.daml" %% "ports" % daml_libraries_version
  lazy val daml_struct_spray_json = "com.daml" %% "struct-spray-json" % daml_libraries_version
  lazy val daml_ledger_resources = "com.daml" %% "ledger-resources" % daml_libraries_version
  lazy val daml_ledger_api_value_proto =
    "com.daml" % "ledger-api-value-proto" % daml_libraries_version
  lazy val daml_ledger_api_value_scalapb =
    "com.daml" %% "ledger-api-value-scalapb" % daml_libraries_version
  lazy val daml_ledger_api_value_java =
    "com.daml" % "ledger-api-value-java-proto" % daml_libraries_version
  lazy val daml_timer_utils = "com.daml" %% "timer-utils" % daml_libraries_version
  lazy val daml_libs_struct_spray_json = "com.daml" %% "struct-spray-json" % daml_libraries_version
  lazy val daml_rs_grpc_pekko = "com.daml" %% "rs-grpc-pekko" % daml_libraries_version
  lazy val daml_rs_grpc_testing_utils =
    "com.daml" %% "rs-grpc-testing-utils" % daml_libraries_version
  lazy val daml_http_test_utils = "com.daml" %% "http-test-utils" % daml_libraries_version
  lazy val daml_testing_utils = "com.daml" %% "testing-utils" % daml_libraries_version

  lazy val bouncycastle_bcprov_jdk15on =
    "org.bouncycastle" % "bcprov-jdk18on" % bouncy_castle_version
  lazy val bouncycastle_bcpkix_jdk15on =
    "org.bouncycastle" % "bcpkix-jdk18on" % bouncy_castle_version

  lazy val grpc_api = "io.grpc" % "grpc-api" % grpc_version
  lazy val grpc_protobuf = "io.grpc" % "grpc-protobuf" % grpc_version
  lazy val grpc_netty_shaded = "io.grpc" % "grpc-netty-shaded" % grpc_version
  lazy val grpc_stub = "io.grpc" % "grpc-stub" % grpc_version
  // pick the version of boring ssl from this table: https://github.com/grpc/grpc-java/blob/master/SECURITY.md#netty
  // required for ALPN (which is required for TLS+HTTP/2) when running on Java 8. JSSE will be used on Java 9+.
  lazy val netty_boring_ssl = "io.netty" % "netty-tcnative-boringssl-static" % "2.0.72.Final"
  lazy val netty_handler = "io.netty" % "netty-handler" % netty_version
  lazy val grpc_services = "io.grpc" % "grpc-services" % grpc_version
  lazy val google_common_protos = "com.google.api.grpc" % "proto-google-common-protos" % "2.41.0"

  lazy val scopt = "com.github.scopt" %% "scopt" % "4.0.0"

  lazy val pekko_actor_typed = "org.apache.pekko" %% "pekko-actor-typed" % pekko_version
  lazy val pekko_stream = "org.apache.pekko" %% "pekko-stream" % pekko_version
  lazy val pekko_stream_testkit = "org.apache.pekko" %% "pekko-stream-testkit" % pekko_version
  lazy val pekko_slf4j = "org.apache.pekko" %% "pekko-slf4j" % pekko_version
  lazy val pekko_http = "org.apache.pekko" %% "pekko-http" % pekko_http_version
  lazy val pekko_http_core = "org.apache.pekko" %% "pekko-http-core" % pekko_http_version
  lazy val pekko_http_testkit = "org.apache.pekko" %% "pekko-http-testkit" % pekko_http_version

  lazy val scala_logging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % scalacheck_version
  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version
  lazy val scalaz_core = "org.scalaz" %% "scalaz-core" % scalaz_version
  lazy val scalatestScalacheck =
    "org.scalatestplus" %% "scalacheck-1-18" % (scalatest_version + ".0")
  lazy val mockito_scala = "org.mockito" %% "mockito-scala" % mockito_scala_version
  lazy val scalatestMockito = "org.scalatestplus" %% "mockito-3-4" % ("3.2.10.0")

  // it should be kept up-to-date with the scaffeine version to avoid incompatibilities
  lazy val caffeine = "com.github.ben-manes.caffeine" % "caffeine" % "3.1.2"
  lazy val scaffeine = "com.github.blemale" %% "scaffeine" % "5.1.2"

  lazy val slf4j_api = "org.slf4j" % "slf4j-api" % slf4j_version
  lazy val jul_to_slf4j = "org.slf4j" % "jul-to-slf4j" % slf4j_version
  lazy val logback_classic =
    "ch.qos.logback" % "logback-classic" % logback_version excludeAll ExclusionRule(
      organization = "org.slf4j",
      name = "slf4j-api",
    )

  lazy val logback_core = "ch.qos.logback" % "logback-core" % logback_version

  lazy val apache_commons_codec = "commons-codec" % "commons-codec" % "1.11"
  lazy val apache_commons_io = "commons-io" % "commons-io" % "2.11.0"
  lazy val log4j_core = "org.apache.logging.log4j" % "log4j-core" % log4j_version
  lazy val log4j_api = "org.apache.logging.log4j" % "log4j-api" % log4j_version

  // used for condition evaluation in logback
  lazy val janino = "org.codehaus.janino" % "janino" % "3.1.4"
  lazy val logstash = "net.logstash.logback" % "logstash-logback-encoder" % "6.6"

  lazy val cats = "org.typelevel" %% "cats-core" % "2.9.0"
  lazy val cats_law = "org.typelevel" %% "cats-laws" % "2.9.0"
  lazy val cats_scalacheck = "io.chrisdavenport" %% "cats-scalacheck" % "0.3.2"

  lazy val chimney = "io.scalaland" %% "chimney" % "1.4.0"

  lazy val magnolia = "com.softwaremill.magnolia1_2" %% "magnolia" % "1.1.3"
  lazy val magnolify_shared = "com.spotify" % "magnolify-shared_2.13" % "0.6.2"
  lazy val magnolify_scalacheck = "com.spotify" % "magnolify-scalacheck_2.13" % "0.6.2"

  lazy val circe_core = "io.circe" %% "circe-core" % circe_version
  lazy val circe_generic = "io.circe" %% "circe-generic" % circe_version
  lazy val circe_generic_extras = "io.circe" %% "circe-generic-extras" % circe_version

  lazy val guava = "com.google.guava" % "guava" % "33.3.0-jre"
  lazy val tink = "com.google.crypto.tink" % "tink" % "1.12.0" excludeAll (
    ExclusionRule(organization = "com.google.guava", name = "guava-jdk5"),
    ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-kms")
  )

  lazy val opentelemetry_version = "1.43.0"
  lazy val opentelemetry_java_instrumentation_version = "2.9.0"
  lazy val opentelemetry_api = "io.opentelemetry" % "opentelemetry-api" % opentelemetry_version
  lazy val opentelemetry_sdk = "io.opentelemetry" % "opentelemetry-sdk" % opentelemetry_version
  lazy val opentelemetry_sdk_testing =
    "io.opentelemetry" % "opentelemetry-sdk-testing" % opentelemetry_version
  lazy val opentelemetry_sdk_autoconfigure =
    "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % s"$opentelemetry_version"
  lazy val opentelemetry_prometheus =
    "io.opentelemetry" % "opentelemetry-exporter-prometheus" % s"$opentelemetry_version-alpha"
  lazy val opentelemetry_zipkin =
    "io.opentelemetry" % "opentelemetry-exporter-zipkin" % opentelemetry_version
  lazy val opentelemetry_trace =
    "io.opentelemetry" % "opentelemetry-exporter-otlp" % opentelemetry_version
  lazy val opentelemetry_instrumentation_grpc =
    "io.opentelemetry.instrumentation" % "opentelemetry-grpc-1.6" % s"$opentelemetry_java_instrumentation_version-alpha"
  lazy val opentelemetry_instrumentation_runtime_metrics =
    "io.opentelemetry.instrumentation" % "opentelemetry-runtime-telemetry-java17" % s"$opentelemetry_java_instrumentation_version-alpha"
  lazy val opentelemetry_instrumentation_hikari =
    "io.opentelemetry.instrumentation" % "opentelemetry-hikaricp-3.0" % s"$opentelemetry_java_instrumentation_version-alpha"
  lazy val opentelemetry_proto = "io.opentelemetry" % "opentelemetry-proto" % "1.7.1-alpha"

  lazy val better_files = "com.github.pathikrit" %% "better-files" % "3.9.2"

  lazy val slick = "com.typesafe.slick" %% "slick" % slick_version
  lazy val slick_hikaricp = "com.typesafe.slick" %% "slick-hikaricp" % slick_version

  lazy val testcontainers_version = "2.0.2"
  lazy val testcontainers = "org.testcontainers" % "testcontainers" % testcontainers_version
  lazy val testcontainers_postgresql =
    "org.testcontainers" % "testcontainers-postgresql" % testcontainers_version

  lazy val sttp_version = "3.8.16"
  lazy val sttp = "com.softwaremill.sttp.client3" %% "core" % sttp_version
  lazy val sttp_okhttp = "com.softwaremill.sttp.client3" %% "okhttp-backend" % sttp_version
  lazy val sttp_circe = "com.softwaremill.sttp.client3" %% "circe" % sttp_version

  lazy val tapir_version = "1.11.7"
  lazy val tapir_json_circe = "com.softwaremill.sttp.tapir" %% "tapir-json-circe" % tapir_version
  lazy val tapir_pekko_http_server =
    "com.softwaremill.sttp.tapir" %% "tapir-pekko-http-server" % tapir_version
  lazy val tapir_openapi_docs =
    "com.softwaremill.sttp.tapir" %% "tapir-openapi-docs" % tapir_version
  lazy val tapir_asyncapi_docs =
    "com.softwaremill.sttp.tapir" %% "tapir-asyncapi-docs" % tapir_version

  lazy val sttp_apiscpec_openapi_circe_yaml =
    "com.softwaremill.sttp.apispec" %% "openapi-circe-yaml" % apispec_version

  lazy val sttp_apiscpec_asyncapi_circe_yaml =
    "com.softwaremill.sttp.apispec" %% "asyncapi-circe-yaml" % apispec_version

  lazy val upickle_version = "4.0.2"

  // Transcode dependencies
  lazy val upickle = "com.lihaoyi" %% "upickle" % upickle_version

  // We have to exclude conflicting parser version
  lazy val ujson_circe =
    "com.lihaoyi" %% "ujson-circe" % upickle_version exclude ("io.circe", "circe-parser_2.13")

  lazy val protostuff_parser = "io.protostuff" % "protostuff-parser" % "3.1.40"

  lazy val toxiproxy_java = "eu.rekawek.toxiproxy" % "toxiproxy-java" % "2.1.4"

  lazy val web3j = "org.web3j" % "core" % "4.8.9"

  lazy val concurrency_limits =
    "com.netflix.concurrency-limits" % "concurrency-limits-grpc" % "0.3.6"

  lazy val wartremover_dep =
    "org.wartremover" % "wartremover" % wartremover.Wart.PluginVersion cross CrossVersion.full

  lazy val scalaz_scalacheck =
    "org.scalaz" %% "scalaz-scalacheck-binding" % "7.2.33-scalacheck-1.15"
  lazy val fasterjackson_core = "com.fasterxml.jackson.core" % "jackson-core" % "2.14.3"
  lazy val scalapb_json4s = "com.thesamet.scalapb" %% "scalapb-json4s" % "0.11.1"

  lazy val junit_jupiter_api = "org.junit.jupiter" % "junit-jupiter-api" % "5.9.2"
  lazy val junit_jupiter_engine = "org.junit.jupiter" % "junit-jupiter-engine" % "5.9.2"
  lazy val junit_platform_runner = "org.junit.platform" % "junit-platform-runner" % "1.9.2"
  lazy val jupiter_interface = "net.aichler" % "jupiter-interface" % "0.9.0"

  lazy val google_protobuf_java = "com.google.protobuf" % "protobuf-java" % "3.25.5"
  lazy val protobuf_version = google_protobuf_java.revision
  lazy val google_protobuf_java_util =
    "com.google.protobuf" % "protobuf-java-util" % protobuf_version

  // AWS SDK for Java API to encrypt/decrypt keys using AWS KMS
  lazy val aws_version = "2.29.5"
  lazy val aws_kms = "software.amazon.awssdk" % "kms" % aws_version
  lazy val aws_sts = "software.amazon.awssdk" % "sts" % aws_version

  // GCP SDK for Java API to encrypt/decrypt keys using GCP KMS
  lazy val gcp_kms_version = "2.55.0"
  lazy val gcp_kms = "com.google.cloud" % "google-cloud-kms" % gcp_kms_version
}
