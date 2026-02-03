// Linting plugins
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "3.3.3")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.4")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.2")
addSbtPlugin("de.heikoseeberger" % "sbt-header" % "5.10.0")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.7")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.17"

addSbtPlugin("com.eed3si9n" %% "sbt-buildinfo" % "0.13.1")

// Our DamlPlugin needs to read and write values from daml.yaml files
// This is a _very_ simple yaml library as we only need to look at two simple keys
libraryDependencies += "com.esotericsoftware.yamlbeans" % "yamlbeans" % "1.17"

libraryDependencies += "com.github.pathikrit" %% "better-files" % "3.9.2"

libraryDependencies += "dev.guardrail" %% "guardrail-scala-pekko-http" % "1.0.0-M1"

val circeVersion = "0.14.1"
libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser",
  "io.circe" %% "circe-optics",
).map(_ % circeVersion)

// Assembly plugin to build fat-jars
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")

addSbtPlugin("dev.guardrail" % "sbt-guardrail" % "1.0.0-M1")

addDependencyTreePlugin

// support for GAR
addSbtPlugin("org.latestbit" % "sbt-gcs-plugin" % "1.16.1")
