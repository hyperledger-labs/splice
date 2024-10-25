// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import sbt.Keys._
import sbt._
import wartremover.WartRemover.autoImport._
import wartremover.contrib.ContribWart

/** Settings for all JVM projects in this build. Contains compiler flags,
  * settings for tests, etc.
  */
object JvmRulesPlugin extends AutoPlugin {
  override def trigger = allRequirements
  override def requires = sbt.plugins.JvmPlugin

  override def projectSettings =
    Seq(
      javacOptions ++= Seq("-encoding", "UTF-8", "-Werror"),
      scalacOptions ++= Seq("-encoding", "UTF-8", "-language:postfixOps"),
      scalacOptions ++= {
        if (System.getProperty("canton-disable-warts") == "true") Seq()
        else
          Seq(
            "-feature",
            "-unchecked",
            "-deprecation",
            "-Xlint:_,-unused",
            "-Xmacro-settings:materialize-derivations",
            "-Xfatal-warnings",
            "-Wconf:cat=lint-byname-implicit:silent", // https://github.com/scala/bug/issues/12072
            "-Wnonunit-statement", // Warns about any interesting expression whose value is ignored because it is followed by another expression
            "-Ywarn-dead-code",
            "-Ywarn-numeric-widen",
            "-Ywarn-value-discard", // Gives a warning for functions declared as returning Unit, but the body returns a value
            "-Vimplicits",
            "-Vtype-diffs",
            "-Wunused:implicits",
            "-Wunused:imports",
            "-Wunused:locals",
            "-Wunused:nowarn",
            "-Xsource:3",
          )
      },
      Test / scalacOptions --= Seq(
        "-Ywarn-value-discard",
        "-Wnonunit-statement",
      ), // disable value discard and nonunit statement checks on tests
      addCompilerPlugin(
        "org.typelevel" % "kind-projector" % "0.13.3" cross CrossVersion.full
      ),
      Compile / compile / wartremoverErrors ++= {
        if (System.getProperty("canton-disable-warts") == "true") Seq()
        else
          Seq(
            Wart.AsInstanceOf,
            Wart.EitherProjectionPartial,
            Wart.Enumeration,
            Wart.IsInstanceOf,
            Wart.JavaConversions,
            Wart.Null,
            Wart.Option2Iterable,
            Wart.OptionPartial,
            Wart.Product,
            Wart.Return,
            Wart.Serializable,
            Wart.IterableOps,
            Wart.TryPartial,
            Wart.Var,
            Wart.While,
            ContribWart.UnintendedLaziness,
          )
      },
      Test / compile / wartremoverErrors := {
        if (System.getProperty("canton-disable-warts") == "true") Seq()
        else
          Seq(
            Wart.EitherProjectionPartial,
            Wart.Enumeration,
            Wart.JavaConversions,
            Wart.Option2Iterable,
            Wart.OptionPartial,
            Wart.Product,
            Wart.Return,
            Wart.Serializable,
            Wart.While,
            ContribWart.UnintendedLaziness,
          )
      },
      // Disable wart checks on generated code
      wartremoverExcluded += (Compile / sourceManaged).value,
      //
      // allow sbt to pull scaladoc from managed dependencies if referenced in our ScalaDoc links
      autoAPIMappings := true,
      //
      // 'slowpoke'/notification message if tests run for more than 5mins, repeat at 30s intervals from there
      Test / testOptions += Tests
        .Argument(TestFrameworks.ScalaTest, "-W", "60", "30"),
      //
      // CHP: Disable output for successful tests
      // G: But after finishing tests for a module, output summary of failed tests for that module, with full stack traces
      // F: Show full stack traces for exceptions in tests (at the time when they occur)
      Test / testOptions += Tests.Argument("-oCHPGF"),
      //
      // to allow notifications and alerts during test runs (like slowpokes^)
      Test / logBuffered := false,
    )

  lazy val scalacOptionsToDisableForTests = Seq(
    "-Ywarn-value-discard",
    "-Wnonunit-statement",
  ) // disable value discard and non-unit statement checks on tests
}
