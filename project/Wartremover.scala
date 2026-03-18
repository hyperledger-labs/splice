// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import BuildCommon.sharedSettings
import sbt.Keys.libraryDependencies
import sbt.{file, Test}
import wartremover.WartRemover.autoImport._

object Wartremover {

  lazy val `splice-wartremover-extension` = {
    import CantonDependencies.*
    sbt.Project
      .apply("splice-wartremover-extension", file("build-tools/wart-remover-extension"))
      .settings(
        sharedSettings,
        libraryDependencies ++= Seq(
          wartremover_dep,
          scalatest % Test,
        ),
      )
  }

  lazy val extraWartRemoverErrors =
    if (sys.env.contains("CI")) Seq(Wart.custom("org.lfdecentralizedtrust.splice.wart.Println"))
    else Seq()

  lazy val spliceWarts = Seq(
    wartremoverErrors ++= extraWartRemoverErrors,
    wartremoverErrors += Wart.custom("org.lfdecentralizedtrust.splice.wart.ParTraverse"),
    wartremover.WartRemover.dependsOnLocalProjectWarts(
      `splice-wartremover-extension`
    ),
  ).flatMap(_.settings)
}
