import BuildCommon.{disableTests, sharedSettings}
import sbt.Keys.libraryDependencies
import sbt.file
import wartremover.WartRemover.autoImport._

object Wartremover {

  lazy val `cn-wartremover-extension` = {
    import CantonDependencies.*
    sbt.Project
      .apply("cn-wartremover-extension", file("build-tools/wart-remover-extension"))
      .settings(
        disableTests,
        sharedSettings,
        libraryDependencies ++= Seq(
          wartremover_dep
        ),
      )
  }

  lazy val cnWarts = Seq(
    wartremoverErrors += Wart.custom("com.daml.network.wart.Println"),
    wartremover.WartRemover.dependsOnLocalProjectWarts(
      `cn-wartremover-extension`
    ),
  ).flatMap(_.settings)
}
