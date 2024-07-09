import sbt.Keys.*
import sbt.*
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{
  HeaderLicense,
  HeaderPattern,
  headerLicense,
  headerMappings,
}
import de.heikoseeberger.sbtheader.{
  LineCommentCreator,
  CommentStyle => HeaderCommentStyle,
  FileType => HeaderFileType,
}
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{headerResources, headerSources}

object Headers {

  lazy val NoHeaderSettings = Seq(
    headerLicense := Some(HeaderLicense.Custom("")),
    headerSources / excludeFilter := "*",
    headerResources / excludeFilter := "*",
  )

  lazy val ApacheDAHeaderSettings = Seq(
    headerLicense := Some(
      HeaderLicense
        .Custom(
          """|Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
             |SPDX-License-Identifier: Apache-2.0
             |""".stripMargin
        )
    ),
    headerSources / excludeFilter := HiddenFileFilter,
    headerResources / excludeFilter := HiddenFileFilter,
    headerMappings := headerMappings.value ++ Map(
      HeaderFileType.scala -> HeaderCommentStyle.cppStyleLineComment
    ),
  )
}
