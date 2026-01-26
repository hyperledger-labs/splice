// Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

import de.heikoseeberger.sbtheader.FileType.firstLinePattern
import sbt.Keys.*
import sbt.*
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport.{
  HeaderLicense,
  HeaderPattern,
  headerEmptyLine,
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

  val ApacheDAHeaderLicense = Some(
    HeaderLicense
      .Custom(
        """|Copyright (c) 2026 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
         |SPDX-License-Identifier: Apache-2.0
         |""".stripMargin
      )
  )

  lazy val ApacheDAHeaderSettings = Seq(
    headerLicense := ApacheDAHeaderLicense,
    headerSources / excludeFilter := HiddenFileFilter,
    headerResources / excludeFilter := HiddenFileFilter,
    Compile / headerSources ++= {
      // Include all daml files, except those in .daml subdirectories
      val damlSources = (
        ((Compile / baseDirectory).value ** "*.daml") ---
          ((Compile / baseDirectory).value ** ".daml" ** "*.daml")
      ).get

      val rstSources = (
        ((Compile / baseDirectory).value ** "*.rst") ---
          ((Compile / baseDirectory).value / "src" / "app_dev" / "api" ** "*.rst")
      ).get

      damlSources ++ rstSources
    },
    headerMappings := allHeaderMappings,
  )

  private def firstLinePattern(firstLinePattern: String) =
    s"""($firstLinePattern(?:\\s+))([\\S\\s]*)""".r

  // Settings for ts files with no empty line after the header, to make `npm prettier` happy
  lazy val TsHeaderSettings = Seq(
    headerLicense := ApacheDAHeaderLicense,
    headerSources / excludeFilter := HiddenFileFilter,
    headerResources / excludeFilter := HiddenFileFilter,
    Compile / headerSources ++= ((Compile / baseDirectory).value ** "*.tsx").get ++
      (
        ((Compile / baseDirectory).value ** "*.ts") ---
          ((Compile / baseDirectory).value ** "node_modules" ** "*") ---
          ((Compile / baseDirectory).value ** "daml.js" ** "*")
      ).get ++
      (
        ((Compile / baseDirectory).value ** "*.js") ---
          ((Compile / baseDirectory).value ** "node_modules" ** "*") ---
          ((Compile / baseDirectory).value ** "daml.js" ** "*") ---
          ((Compile / baseDirectory).value ** "lib" ** "*")
      ).get,
    headerMappings := allHeaderMappings,
    headerEmptyLine := false,
  )

  // Header settings for anything that's not otherwise processes by SBT
  // (to be included in the root Compile settings)
  lazy val OtherHeaderSettings = Seq(
    headerLicense := ApacheDAHeaderLicense,
    headerSources / excludeFilter := HiddenFileFilter,
    headerResources / excludeFilter := HiddenFileFilter,
    Compile / headerSources := {
      val pySources = (
        ((Compile / baseDirectory).value ** "*.py") ---
          ((Compile / baseDirectory).value ** "configs" ** "*") ---
          ((Compile / baseDirectory).value ** "configs-private" ** "*") ---
          ((Compile / baseDirectory).value ** "community" ** "*") ---
          ((Compile / baseDirectory).value ** "node_modules" ** "*")
      ).get

      val shSources = (
        ((Compile / baseDirectory).value ** "*.sh") ---
          ((Compile / baseDirectory).value ** "node_modules" ** "*") ---
          ((Compile / baseDirectory).value ** "configs" ** "*") ---
          ((Compile / baseDirectory).value ** "configs-private" ** "*") ---
          ((Compile / baseDirectory).value ** "community" ** "*") ---
          ((Compile / baseDirectory).value ** "*ts-client" ** "*") ---
          ((Compile / baseDirectory).value ** "target" ** "*") ---
          ((Compile / baseDirectory).value ** "cmd-*.sh") ---
          ((Compile / baseDirectory).value ** "example-script.sh")
      ).get

      val sbtScalaSources = (
        (Compile / baseDirectory).value / "project" ** "*.scala"
      ).get

      val mkSources = (
        (Compile / baseDirectory).value ** "*.mk"
      ).get

      val helmSources = (
        (Compile / baseDirectory).value / "cluster" / "helm" ** "*.yaml"
      ).get

      pySources ++ shSources ++ sbtScalaSources ++ mkSources ++ helmSources
    },
    headerMappings := allHeaderMappings,
  )

  lazy val allHeaderMappings = Map(
    HeaderFileType.scala -> scalaCommentStyle,
    HeaderFileType.sh -> hashCommentStyle,
    HeaderFileType("py", Some(firstLinePattern("#!.*"))) -> hashCommentStyle,
    HeaderFileType("daml") -> dashCommentStyle,
    HeaderFileType("rst", Some(firstLinePattern(":.*:"))) -> dotCommentStyle,
    HeaderFileType("tsx") -> tsCommentStyle,
    HeaderFileType("ts") -> tsCommentStyle,
    HeaderFileType("js") -> tsCommentStyle,
    HeaderFileType("mk") -> hashCommentStyle,
    HeaderFileType("yaml") -> hashCommentStyle,
  )

  lazy val scalaCommentStyle = HeaderCommentStyle(
    new LineCommentCreator("//"),
    commentStartingWith("//"),
  )

  lazy val tsCommentStyle = HeaderCommentStyle(
    new LineCommentCreator("//", false),
    commentStartingWith("//"),
  )

  lazy val dashCommentStyle = HeaderCommentStyle(
    new LineCommentCreator("--"),
    commentStartingWith("--"),
  )

  lazy val dotCommentStyle = HeaderCommentStyle(
    new LineCommentCreator(s"..${System.lineSeparator()}  "),
    HeaderPattern.commentStartingWith(s"..${System.lineSeparator()}  "),
  )

  lazy val hashCommentStyle = HeaderCommentStyle(
    new LineCommentCreator("#"),
    commentStartingWith("#"),
  )

  // A comment creator that preserves existing comments at the beginning of the
  // file if they are not a copyright notice
  final class LineCommentCreator(linePrefix: String, headerEmptyLine: Boolean = true)
      extends CommentCreator {
    override def apply(text: String, existingText: Option[String]): String = {
      def prependWithLinePrefix(s: String) =
        s match {
          case "" => if (linePrefix.trim.nonEmpty) linePrefix else ""
          case line => s"$linePrefix $line"
        }

      val pattern = (s"(^${linePrefix} Copyright.*\\n${linePrefix} SPDX.*\\n)([\\s\\S]*)").r
      val existingCommentIfNotCopyright = existingText
        .map({
          case pattern(_, "\n") =>
            if (headerEmptyLine) { "" }
            else { "\n" }
          case pattern(_, "") => ""
          case pattern(_, comment) =>
            (if (headerEmptyLine) { "\n" }
             else { "" }) +
              "\n" + comment.trim
          case nonCopyright =>
            (if (headerEmptyLine) { "\n" }
             else { "" }) +
              "\n" + nonCopyright.trim
        })
        .getOrElse("")
      text.linesIterator.map(prependWithLinePrefix).mkString("\n") + existingCommentIfNotCopyright
    }
  }
}
