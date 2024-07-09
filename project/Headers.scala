import sbt.Keys.*
import sbt.*
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{
  HeaderLicense,
  HeaderPattern,
  headerLicense,
  headerMappings,
}
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.HeaderPattern.commentStartingWith
import de.heikoseeberger.sbtheader.{
  CommentCreator,
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
    // Include all daml files, except those in .daml subdirectories
    Compile / headerSources ++=
      (((Compile / baseDirectory).value ** "*.daml") ---
        ((Compile / baseDirectory).value ** ".daml" ** "*.daml")).get,
    headerMappings := headerMappings.value ++ Map(
      HeaderFileType.scala -> scalaCommentStyle,
      HeaderFileType("daml") -> dashCommentStyle,
    ),
  )

  lazy val scalaCommentStyle = HeaderCommentStyle(
    new LineCommentCreator("//"),
    commentStartingWith("//"),
  )

  lazy val dashCommentStyle = HeaderCommentStyle(
    new LineCommentCreator("--"),
    commentStartingWith("--"),
  )

  // A comment creator that preserves existing comments at the beginning of the
  // file if they are not a copyright notice
  final class LineCommentCreator(linePrefix: String) extends CommentCreator {
    override def apply(text: String, existingText: Option[String]): String = {
      def prependWithLinePrefix(s: String) =
        s match {
          case "" => if (linePrefix.trim.nonEmpty) linePrefix else ""
          case line => s"$linePrefix $line"
        }

      val pattern = (s"(^${linePrefix} Copyright[\\s\\S]*)").r
      val existingCommentIfNotCopyright = existingText
        .map({
          case pattern(_) => ""
          case nonCopyright => "\n\n" + nonCopyright
        })
        .getOrElse("")
      text.linesIterator.map(prependWithLinePrefix).mkString("\n") + existingCommentIfNotCopyright
    }
  }
}
