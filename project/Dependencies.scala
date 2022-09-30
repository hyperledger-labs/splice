import sbt._

object Dependencies {
  lazy val scala_version = "2.13.8"

  lazy val scalatest_version = "3.2.9"

  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version

  // Picked up automatically by the scalapb compiler. Contains common dependencies such as protocol buffers like google/protobuf/timestamp.proto
  lazy val scalapb_runtime =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  lazy val scalapb_runtime_grpc =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion

  lazy val daml_ledger_api_scalapb =
    "com.daml" %% "ledger-api-scalapb" % CantonDependencies.daml_libraries_version

  lazy val daml_ledger_api_proto =
    "com.daml" % "ledger-api-proto" % CantonDependencies.daml_libraries_version

  lazy val daml_bindings_scala =
    "com.daml" %% "bindings-scala" % CantonDependencies.daml_libraries_version
}
