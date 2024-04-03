package com.daml.network.build_tools

import better.files.*
import com.daml.lf.archive.{DarDecoder, DarParser}

object DarLockChecker {
  def main(args: Array[String]): Unit = {
    args.toSeq match {
      case cmd +: outputFileName +: inputFileNames =>
        val dars = inputFileNames
          .map(filename => {
            val hash = DarParser.assertReadArchiveFromFile(File(filename).toJava).main.getHash
            val metadata =
              DarDecoder.assertReadArchiveFromFile(File(filename).toJava).main._2.metadata
            (metadata.name, metadata.version.toString()) -> hash
          })
          .foldLeft(Map.empty[(String, String), String]) {
            case (map, ((name, version), hash)) => {
              val _ = map
                .get((name, version))
                .foreach(_hash =>
                  if (_hash != hash)
                    sys.error(
                      s"Conflicting hashes for version ${version} of package ${name}. If you modified daml models, please bump the package version."
                    )
                )
              map + ((name, version) -> hash)
            }
          }
        val lockStr = dars
          .map({ case (name, version) -> hash => s"$name $version $hash" })
          .toSeq
          .sorted
          .mkString(System.lineSeparator())
        cmd match {
          case "check" =>
            val currentHashes = File(outputFileName).contentAsString
            if (currentHashes != lockStr)
              sys.error(
                Seq(
                  "Error: daml lockfile is not up-to-date",
                  "Expected:",
                  lockStr,
                  "Actual:",
                  currentHashes,
                ).mkString(System.lineSeparator())
              )
          case "update" =>
            val _ = File(outputFileName).overwrite(lockStr)
          case _ =>
            printHelpAndError(s"unknown command '$cmd'")
        }
      case _ =>
        printHelpAndError("not enough arguments")
    }
  }

  private def printHelpAndError(reason: String): Unit =
    sys.error(
      s"Error: $reason\nSynopsis: dar-mananger (check|update) <outputFile> <inputDar>*"
    )
}
