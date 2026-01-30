// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import sbt.*

object Dependencies {
  private final val CD = CantonDependencies

  lazy val scala_version = "2.13.16"

  lazy val scalatest_version = "3.2.19"

  lazy val scalatest = "org.scalatest" %% "scalatest" % scalatest_version

  lazy val scalatestScalacheck = CD.scalatestScalacheck

  // Picked up automatically by the scalapb compiler. Contains common dependencies such as protocol buffers like google/protobuf/timestamp.proto
  lazy val scalapb_runtime =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  lazy val scalapb_runtime_grpc =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
  lazy val scalapb_json4 = "com.thesamet.scalapb" %% "scalapb-json4s" % "0.11.1"

  lazy val auth0 = "com.auth0" % "auth0" % "1.44.1"

  lazy val java_jwt =
    "com.auth0" % "java-jwt" % "4.1.0"

  lazy val jwks_rsa =
    "com.auth0" % "jwks-rsa" % "0.21.2"

  lazy val daml_lf_archive_reader =
    "com.daml" %% "daml-lf-archive-reader" % CantonDependencies.daml_libraries_version

  lazy val daml_lf_validation =
    "com.daml" %% "daml-lf-validation" % CantonDependencies.daml_libraries_version

  lazy val pekko_http_cors =
    "org.apache.pekko" %% "pekko-http-cors" % CantonDependencies.pekko_http_version

  lazy val pekko_connectors_google_cloud_storage =
    "org.apache.pekko" %% "pekko-connectors-google-cloud-storage" % "1.0.2"

  lazy val spray_json =
    "io.spray" %% "spray-json" % "1.3.6"

  lazy val pekko_spray_json =
    "org.apache.pekko" %% "pekko-http-spray-json" % CantonDependencies.pekko_http_version

  lazy val better_files = CantonDependencies.better_files

  lazy val google_cloud_storage =
    "com.google.cloud" % "google-cloud-storage" % "2.44.1"

  lazy val jaxb_abi = "javax.xml.bind" % "jaxb-api" % "2.3.1"

  lazy val commons_compress = "org.apache.commons" % "commons-compress" % "1.27.1"

  lazy val kubernetes_client = "io.fabric8" % "kubernetes-client" % "6.8.1" % "provided"

  lazy val parallel_collections = "org.scala-lang.modules" %% "scala-parallel-collections" % "1.0.4"

  lazy val zstd = "com.github.luben" % "zstd-jni" % "1.5.7-6"

  lazy val aws_s3 = "software.amazon.awssdk" % "s3" % CantonDependencies.aws_version

  lazy val s3mock_testcontainers = "com.adobe.testing" % "s3mock-testcontainers" % "4.11.0" % "test"
}
