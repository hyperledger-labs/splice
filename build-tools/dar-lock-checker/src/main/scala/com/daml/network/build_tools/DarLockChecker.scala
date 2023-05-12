package com.daml.network.build_tools

import better.files.*
import com.daml.lf.archive.DarParser

object DarLockChecker {
  def main(args: Array[String]): Unit = {
    args.toSeq match {
      case cmd +: outputFileName +: inputFileNames =>
        val darHashes = inputFileNames
          .map(name => {
            val hash = DarParser.assertReadArchiveFromFile(File(name).toJava).main.getHash
            name + " " + hash + System.lineSeparator()
          })
          .sorted
          .mkString
        cmd match {
          case "check" =>
            val currentHashes = File(outputFileName).contentAsString
            if (currentHashes != darHashes)
              sys.error(
                Seq(
                  "Error: .dar file hashes do not match",
                  "Expected:",
                  darHashes,
                  "Actual:",
                  currentHashes,
                ).mkString(System.lineSeparator())
              )
          case "update" =>
            val _ = File(outputFileName).overwrite(darHashes)
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
