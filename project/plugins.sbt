// Linting plugins
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "2.4.16")
addSbtPlugin("org.wartremover" % "sbt-wartremover-contrib" % "1.3.13")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.3")

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.6")
libraryDependencies += "com.thesamet.scalapb" %% "compilerplugin" % "0.11.8"

addSbtPlugin("com.eed3si9n" %% "sbt-buildinfo" % "0.9.0")

// Our DamlPlugin needs to read and write values from daml.yaml files
// This is a _very_ simple yaml library as we only need to look at two simple keys
libraryDependencies += "com.esotericsoftware.yamlbeans" % "yamlbeans" % "1.13"

libraryDependencies += "com.github.pathikrit" %% "better-files" % "3.8.0"
