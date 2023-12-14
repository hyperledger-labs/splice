import sbt._

/** Copied from Canton OSS repo. */
object CantonDependencies {
  // Slightly changed compared to Canton OSS repo to avoid the need for a meta sbt project
  val version: String = "2.9.0-snapshot.20231211.12491.0.vbb56339f"
  val daml_language_versions = Seq("1.14", "1.15", "1.dev")
  val vmbc_driver_libraries_version: String =
    "2.3.0-snapshot.20220528.9973.0.012e3ac6-0.1"
  val daml_libraries_version = version
  val daml_compiler_version = version
  // TODO(#8069) Revert this back to daml_libraries_version.
  val daml_java_codegen_version = "2.9.0-snapshot.20231129.12441.0.v72cefd6f"
  val use_custom_daml_version = false

  lazy val osClassifier: String =
    if (sys.props("os.name").contains("Mac")) "osx"
    else sys.props("os.name").toLowerCase

  lazy val scala_version = "2.13.11"
  lazy val scala_version_short = "2.13"

  lazy val anorm = "org.playframework.anorm" %% "anorm" % "2.7.0"
  lazy val pekko_version = "1.0.1"
  lazy val pekko_http_version = "1.0.0"
  lazy val auth0_java = "com.auth0" % "java-jwt" % "4.2.1"
  lazy val auth0_jwks = "com.auth0" % "jwks-rsa" % "0.21.2"
  lazy val awaitility = "org.awaitility" % "awaitility" % "4.2.0"
  lazy val grpc_version = "1.59.0"
  lazy val logback_version = "1.4.5"
  lazy val slf4j_version = "2.0.6"
  lazy val log4j_version = "2.17.0"
  lazy val ammonite_version = "2.5.5"
  lazy val pprint_version = "0.7.1"
  // if you update the slick version, please also update our forked code in common/slick.util.*
  lazy val slick_version = "3.3.3"
  lazy val bouncy_castle_version = "1.70"

  lazy val pureconfig_version = "0.14.0"

  lazy val circe_version = "0.13.0"

  lazy val scalatest_version = "3.2.9"

  lazy val netty_version = "4.1.97.Final"

  lazy val reflections = "org.reflections" % "reflections" % "0.9.12"
  lazy val pureconfig = "com.github.pureconfig" %% "pureconfig" % pureconfig_version
  lazy val pureconfig_cats = "com.github.pureconfig" %% "pureconfig-cats" % pureconfig_version

  lazy val scala_collection_contrib =
    "org.scala-lang.modules" %% "scala-collection-contrib" % "0.2.2"
  lazy val scala_reflect = "org.scala-lang" % "scala-reflect" % scala_version
  lazy val shapeless = "com.chuusai" %% "shapeless" % "2.3.6"

  lazy val monocle_version = "3.1.0"
  lazy val monocle_core = "dev.optics" %% "monocle-core" % monocle_version
  lazy val monocle_macro = "dev.optics" %% "monocle-macro" % monocle_version

  // ammonite requires the full scala version including patch number
  lazy val ammonite = "com.lihaoyi" % "ammonite" % ammonite_version cross CrossVersion.full
  lazy val pprint = "com.lihaoyi" %% "pprint" % pprint_version

  lazy val hikaricp = "com.zaxxer" % "HikariCP" % "3.2.0"
  lazy val h2 = "com.h2database" % "h2" % "2.1.210"
  lazy val postgres = "org.postgresql" % "postgresql" % "42.2.25"
  lazy val flyway = "org.flywaydb" % "flyway-core" % "8.4.0"
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
  lazy val daml_lf_dev_archive_java_proto =
    "com.daml" % "daml-lf-dev-archive-java-proto" % daml_libraries_version
  lazy val daml_lf_archive_reader = "com.daml" %% "daml-lf-archive-reader" % daml_libraries_version
  lazy val daml_lf_data = "com.daml" %% "daml-lf-data" % daml_libraries_version
  lazy val daml_lf_engine = "com.daml" %% "daml-lf-engine" % daml_libraries_version
  lazy val daml_lf_value_java_proto =
    "com.daml" % "daml-lf-value-java-proto" % daml_libraries_version
  lazy val daml_lf_transaction = "com.daml" %% "daml-lf-transaction" % daml_libraries_version
  lazy val daml_lf_transaction_test_lib =
    "com.daml" %% "daml-lf-transaction-test-lib" % daml_libraries_version
  lazy val daml_lf_api_type_signature =
    "com.daml" %% "daml-lf-api-type-signature" % daml_libraries_version

  lazy val daml_grpc_utils = "com.daml" %% "grpc-utils" % daml_libraries_version
  lazy val daml_nonempty_cats = "com.daml" %% "nonempty-cats" % daml_libraries_version
  lazy val daml_metrics = "com.daml" %% "metrics" % daml_libraries_version
  lazy val daml_metrics_test_lib = "com.daml" %% "metrics-test-lib" % daml_libraries_version
  lazy val daml_contextualized_logging =
    "com.daml" %% "contextualized-logging" % daml_libraries_version
  lazy val daml_ledger_rxjava_client = "com.daml" % "bindings-rxjava" % daml_libraries_version
  lazy val daml_telemetry = "com.daml" %% "telemetry" % daml_libraries_version
  lazy val daml_pekko_http_metrics = "com.daml" %% "pekko-http-metrics" % daml_libraries_version
  lazy val daml_tracing = "com.daml" %% "tracing" % daml_libraries_version
  lazy val daml_tracing_test_lib = "com.daml" %% "tracing-test-lib" % daml_libraries_version
  lazy val daml_executors = "com.daml" %% "executors" % daml_libraries_version
  lazy val daml_jwt = "com.daml" %% "jwt" % daml_libraries_version
  lazy val daml_ports = "com.daml" %% "ports" % daml_libraries_version
  lazy val daml_struct_spray_json = "com.daml" %% "struct-spray-json" % daml_libraries_version
  lazy val daml_bindings_scala = "com.daml" %% "bindings-scala" % daml_java_codegen_version
  lazy val daml_ledger_resources = "com.daml" %% "ledger-resources" % daml_libraries_version
  lazy val daml_timer_utils = "com.daml" %% "timer-utils" % daml_libraries_version
  lazy val daml_rs_grpc_pekko = "com.daml" %% "rs-grpc-pekko" % daml_libraries_version
  lazy val daml_rs_grpc_testing_utils =
    "com.daml" %% "rs-grpc-testing-utils" % daml_libraries_version
  lazy val daml_http_test_utils = "com.daml" %% "http-test-utils" % daml_libraries_version
  lazy val daml_testing_utils = "com.daml" %% "testing-utils" % daml_libraries_version

  lazy val bouncycastle_bcprov_jdk15on =
    "org.bouncycastle" % "bcprov-jdk15on" % bouncy_castle_version
  lazy val bouncycastle_bcpkix_jdk15on =
    "org.bouncycastle" % "bcpkix-jdk15on" % bouncy_castle_version

  lazy val grpc_api = "io.grpc" % "grpc-api" % grpc_version
  lazy val grpc_protobuf = "io.grpc" % "grpc-protobuf" % grpc_version
  lazy val grpc_netty = "io.grpc" % "grpc-netty" % grpc_version
  // pick the version of boring ssl from this table: https://github.com/grpc/grpc-java/blob/master/SECURITY.md#netty
  // required for ALPN (which is required for TLS+HTTP/2) when running on Java 8. JSSE will be used on Java 9+.
  lazy val netty_boring_ssl = "io.netty" % "netty-tcnative-boringssl-static" % "2.0.46.Final"
  lazy val netty_handler = "io.netty" % "netty-handler" % netty_version
  lazy val grpc_services = "io.grpc" % "grpc-services" % grpc_version
  lazy val google_common_protos = "com.google.api.grpc" % "proto-google-common-protos" % "2.0.1"

  lazy val scopt = "com.github.scopt" %% "scopt" % "4.0.0"

  lazy val pekko_stream = "org.apache.pekko" %% "pekko-stream" % pekko_version
  lazy val pekko_stream_testkit = "org.apache.pekko" %% "pekko-stream-testkit" % pekko_version
  lazy val pekko_slf4j = "org.apache.pekko" %% "pekko-slf4j" % pekko_version
  lazy val pekko_http = "org.apache.pekko" %% "pekko-http" % pekko_http_version
  lazy val pekko_http_core = "org.apache.pekko" %% "pekko-http-core" % pekko_http_version
  lazy val pekko_http_testkit = "org.apache.pekko" %% "pekko-http-testkit" % pekko_http_version

  lazy val scala_logging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
  lazy val scalacheck = "org.scalacheck" %% "scalacheck" % "1.15.4"
  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version
  lazy val scalatestScalacheck =
    "org.scalatestplus" %% "scalacheck-1-15" % (scalatest_version + ".0")
  lazy val mockito_scala = "org.mockito" %% "mockito-scala" % "1.16.3"
  lazy val scalatestMockito = "org.scalatestplus" %% "mockito-3-4" % (scalatest_version + ".0")

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

  lazy val cats = "org.typelevel" %% "cats-core" % "2.6.1"
  lazy val cats_law = "org.typelevel" %% "cats-laws" % "2.6.1"
  lazy val cats_scalacheck = "io.chrisdavenport" %% "cats-scalacheck" % "0.2.0"

  lazy val chimney = "io.scalaland" %% "chimney" % "0.6.1"

  lazy val magnolia = "com.softwaremill.magnolia1_2" %% "magnolia" % "1.1.3"
  lazy val magnolify_shared = "com.spotify" % "magnolify-shared_2.13" % "0.6.2"
  lazy val magnolify_scalacheck = "com.spotify" % "magnolify-scalacheck_2.13" % "0.6.2"

  lazy val circe_core = "io.circe" %% "circe-core" % circe_version
  lazy val circe_generic = "io.circe" %% "circe-generic" % circe_version
  lazy val circe_generic_extras = "io.circe" %% "circe-generic-extras" % circe_version

  lazy val guava = "com.google.guava" % "guava" % "31.1-jre"
  lazy val tink = "com.google.crypto.tink" % "tink" % "1.3.0" excludeAll (
    ExclusionRule(organization = "com.google.guava", name = "guava-jdk5"),
    ExclusionRule(organization = "com.amazonaws", name = "aws-java-sdk-kms")
  )

  lazy val dropwizard_metrics_core = "io.dropwizard.metrics" % "metrics-core" % "4.1.2"
  lazy val dropwizard_metrics_jmx = "io.dropwizard.metrics" % "metrics-jmx" % "4.1.2"
  lazy val dropwizard_metrics_jvm = "io.dropwizard.metrics" % "metrics-jvm" % "4.1.2"
  lazy val dropwizard_metrics_graphite = "io.dropwizard.metrics" % "metrics-graphite" % "4.1.2"

  lazy val prometheus_dropwizard = "io.prometheus" % "simpleclient_dropwizard" % "0.12.0"
  lazy val prometheus_httpserver = "io.prometheus" % "simpleclient_httpserver" % "0.12.0"
  lazy val prometheus_hotspot = "io.prometheus" % "simpleclient_hotspot" % "0.12.0"

  lazy val opentelemetry_version = "1.12.0"
  lazy val opentelemetry_api = "io.opentelemetry" % "opentelemetry-api" % opentelemetry_version
  lazy val opentelemetry_context =
    "io.opentelemetry" % "opentelemetry-context" % opentelemetry_version
  lazy val opentelemetry_semconv =
    "io.opentelemetry" % "opentelemetry-semconv" % s"$opentelemetry_version-alpha"
  lazy val opentelemetry_sdk = "io.opentelemetry" % "opentelemetry-sdk" % opentelemetry_version
  lazy val opentelemetry_sdk_autoconfigure =
    "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % s"$opentelemetry_version-alpha"
  lazy val opentelemetry_sdk_common =
    "io.opentelemetry" % "opentelemetry-sdk-common" % opentelemetry_version
  lazy val opentelemetry_sdk_logs =
    "io.opentelemetry" % "opentelemetry-sdk-logs" % s"$opentelemetry_version-alpha"
  lazy val opentelemetry_sdk_metrics =
    "io.opentelemetry" % "opentelemetry-sdk-metrics" % s"$opentelemetry_version-alpha"
  lazy val opentelemetry_sdk_testing =
    "io.opentelemetry" % "opentelemetry-sdk-testing" % opentelemetry_version
  lazy val opentelemetry_sdk_trace =
    "io.opentelemetry" % "opentelemetry-sdk-trace" % opentelemetry_version
  lazy val opentelemetry_prometheus =
    "io.opentelemetry" % "opentelemetry-exporter-prometheus" % s"$opentelemetry_version-alpha"
  lazy val opentelemetry_zipkin =
    "io.opentelemetry" % "opentelemetry-exporter-zipkin" % opentelemetry_version
  lazy val opentelemetry_jaeger =
    "io.opentelemetry" % "opentelemetry-exporter-jaeger" % opentelemetry_version
  lazy val opentelemetry_trace =
    "io.opentelemetry" % "opentelemetry-exporter-otlp-trace" % opentelemetry_version
  lazy val opentelemetry_instrumentation_grpc =
    "io.opentelemetry.instrumentation" % "opentelemetry-grpc-1.6" % s"$opentelemetry_version-alpha"

  lazy val better_files = "com.github.pathikrit" %% "better-files" % "3.8.0"

  lazy val slick = "com.typesafe.slick" %% "slick" % slick_version
  lazy val slick_hikaricp = "com.typesafe.slick" %% "slick-hikaricp" % slick_version

  lazy val testcontainers_version = "1.15.1"
  lazy val testcontainers = "org.testcontainers" % "testcontainers" % testcontainers_version
  lazy val testcontainers_postgresql = "org.testcontainers" % "postgresql" % testcontainers_version

  lazy val sttp_version = "3.1.7"
  lazy val sttp = "com.softwaremill.sttp.client3" %% "core" % sttp_version
  lazy val sttp_okhttp = "com.softwaremill.sttp.client3" %% "okhttp-backend" % sttp_version
  lazy val sttp_circe = "com.softwaremill.sttp.client3" %% "circe" % sttp_version

  lazy val toxiproxy_java = "eu.rekawek.toxiproxy" % "toxiproxy-java" % "2.1.4"

  lazy val web3j = "org.web3j" % "core" % "4.8.9"

  lazy val concurrency_limits =
    "com.netflix.concurrency-limits" % "concurrency-limits-grpc" % "0.3.6"

  lazy val wartremover_dep =
    "org.wartremover" % "wartremover" % wartremover.Wart.PluginVersion cross CrossVersion.full

  lazy val scalaz_scalacheck =
    "org.scalaz" %% "scalaz-scalacheck-binding" % "7.2.33-scalacheck-1.15"
  lazy val scalapb_json4s = "com.thesamet.scalapb" %% "scalapb-json4s" % "0.11.1"

  lazy val spray_json_derived_codecs =
    "io.github.paoloboni" %% "spray-json-derived-codecs" % "2.3.10"
}
