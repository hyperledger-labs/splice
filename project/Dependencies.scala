import sbt.*

object Dependencies {
  private final val CD = CantonDependencies

  lazy val scala_version = "2.13.10"

  lazy val scalatest_version = "3.2.9"

  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version

  lazy val scalatestScalacheck = CD.scalatestScalacheck

  // Picked up automatically by the scalapb compiler. Contains common dependencies such as protocol buffers like google/protobuf/timestamp.proto
  lazy val scalapb_runtime =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  lazy val scalapb_runtime_grpc =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion

  lazy val daml_ledger_api_scalapb =
    "com.daml" %% "ledger-api-scalapb" % CantonDependencies.daml_libraries_version

  lazy val daml_ledger_api_proto =
    "com.daml" % "ledger-api-proto" % CantonDependencies.daml_libraries_version

  lazy val java_jwt =
    "com.auth0" % "java-jwt" % "4.1.0"

  lazy val jwks_rsa =
    "com.auth0" % "jwks-rsa" % "0.21.2"

  lazy val daml_bindings_java =
    "com.daml" % "bindings-java" % CantonDependencies.daml_libraries_version

  lazy val daml_lf_value_json =
    "com.daml" %% "lf-value-json" % CantonDependencies.daml_libraries_version

  lazy val akka_http_cors =
    "ch.megard" %% "akka-http-cors" % "1.1.3"

  lazy val spray_json =
    "io.spray" %% "spray-json" % "1.3.6"

  lazy val akka_spray_json =
    "com.typesafe.akka" %% "akka-http-spray-json" % "10.2.8"
}
