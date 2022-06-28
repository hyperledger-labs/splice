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
            "-Ywarn-dead-code",
            "-Ywarn-numeric-widen",
            "-Ywarn-value-discard", // Gives a warning for functions declared as returning Unit, but the body returns a value
            // TODO(i87): re-enable
//            "-Ywarn-unused:imports",
            "-Ywarn-unused:implicits",
            // TODO(i87): re-enable
//            "-Ywarn-unused:locals",
            "-Vimplicits",
            "-Vtype-diffs",
          )
      },
      Test / scalacOptions --= Seq("-Ywarn-value-discard"), // disable value discard check on tests
      addCompilerPlugin(
        "org.typelevel" % "kind-projector" % "0.13.2" cross CrossVersion.full
      ),
      wartremoverErrors ++= {
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
            Wart.TraversableOps,
            Wart.TryPartial,
            Wart.Var,
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
        .Argument(TestFrameworks.ScalaTest, "-W", "120", "30"),
      //
      // CHP: Disable output for successful tests
      // G: But after finishing tests for a module, output summary of failed tests for that module, with full stack traces
      // F: Show full stack traces for exceptions in tests (at the time when they occur)
      Test / testOptions += Tests.Argument("-oCHPGF"),
      //
      // to allow notifications and alerts during test runs (like slowpokes^)
      Test / logBuffered := false,
    )
}
