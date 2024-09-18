// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import BuildCommon.{disableTests, sharedSettings}
import sbt.Keys.libraryDependencies
import sbt.file
import wartremover.WartRemover.autoImport._

object Wartremover {

  lazy val `splice-wartremover-extension` = {
    import CantonDependencies.*
    sbt.Project
      .apply("splice-wartremover-extension", file("build-tools/wart-remover-extension"))
      .settings(
        disableTests,
        sharedSettings,
        libraryDependencies ++= Seq(
          wartremover_dep
        ),
      )
  }

  lazy val extraWartRemoverErrors =
    if (sys.env.contains("CI")) Seq(Wart.custom("com.daml.network.wart.Println"))
    else Seq()

  lazy val spliceWarts = Seq(
    wartremoverErrors ++= extraWartRemoverErrors,
    wartremover.WartRemover.dependsOnLocalProjectWarts(
      `splice-wartremover-extension`
    ),
  ).flatMap(_.settings)
}
