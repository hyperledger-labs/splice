import sbt.*

object Dependencies {
  private final val CD = CantonDependencies

  lazy val scala_version = "2.13.10"

  lazy val scalatest_version = "3.2.9"

  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version

  lazy val scalatestScalacheck = CD.scalatestScalacheck

  // We may want to remove this in favor of CantonDependencies.daml_libraries_version
  // at some point but they can also be decoupled.
  lazy val ledgerApiVersion = "2.7.0-snapshot.20230612.11864.0.v3514a4a0"

  // Picked up automatically by the scalapb compiler. Contains common dependencies such as protocol buffers like google/protobuf/timestamp.proto
  lazy val scalapb_runtime =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  lazy val scalapb_runtime_grpc =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion

  lazy val daml_ledger_api_scalapb =
    "com.daml" %% "ledger-api-scalapb" % ledgerApiVersion

  lazy val daml_ledger_api_proto =
    "com.daml" % "ledger-api-proto" % ledgerApiVersion

  lazy val auth0 = "com.auth0" % "auth0" % "1.44.1"

  lazy val java_jwt =
    "com.auth0" % "java-jwt" % "4.1.0"

  lazy val jwks_rsa =
    "com.auth0" % "jwks-rsa" % "0.21.2"

  lazy val daml_lf_archive_reader =
    "com.daml" %% "daml-lf-archive-reader" % CantonDependencies.daml_libraries_version

  lazy val daml_bindings_java =
    "com.daml" % "bindings-java" % CantonDependencies.daml_libraries_version

  lazy val daml_lf_value_json =
    "com.daml" %% "lf-value-json" % CantonDependencies.daml_libraries_version

  lazy val daml_lf_validation =
    "com.daml" %% "daml-lf-validation" % CantonDependencies.daml_libraries_version

  lazy val akka_http_cors =
    "ch.megard" %% "akka-http-cors" % "1.1.3"

  lazy val spray_json =
    "io.spray" %% "spray-json" % "1.3.6"

  lazy val akka_spray_json =
    "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.8"

  lazy val better_files = CantonDependencies.better_files

  lazy val comet_bft_proto =
    "com.digitalasset.canton.drivers" % "canton-drivers-proto" % sys.env("COMETBFT_RELEASE_VERSION")

}
