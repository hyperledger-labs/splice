package com.daml.network.build_tools

import better.files.*
import com.daml.lf.archive.{DarDecoder, DarParser}
import scala.sys.process.*

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
            checkDarsLockImmutable()
          case "update" =>
            val _ = File(outputFileName).overwrite(lockStr)
            checkDarsLockImmutable()
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

  private def checkDarsLockImmutable(): Unit = {
    val lastReleaseNumber = File("LATEST_RELEASE").contentAsString.strip
    val lastReleaseDarLock =
      s"git show refs/remotes/origin/release-line-$lastReleaseNumber:daml/dars.lock".!!
    val lastReleaseDars = parseDarsLock(lastReleaseDarLock)
    val currentDars = parseDarsLock(File("daml/dars.lock").contentAsString)
    currentDars.foreach { case (pkg, currentHash) =>
      lastReleaseDars.get(pkg).foreach { lastReleaseHash =>
        if (currentHash != lastReleaseHash) {
          sys.error(
            s"Package $pkg changed hash from $currentHash to $lastReleaseHash, did you forget to bump the package version?"
          )
        }
      }
    }
    lastReleaseDars.keys.foreach { case pkg @ (pkgName, _) =>
      if (!pkgName.endsWith("-test") && !currentDars.contains(pkg)) {
        sys.error(s"Package $pkg was in last release but is missing from current release")
      }
    }
  }

  // Parse the contents of the dar lock file into
  // a map of (package name, package version) -> package id
  private def parseDarsLock(fileContent: String): Map[(String, String), String] = {
    fileContent
      .split("\n")
      .map { line =>
        line.split(" ") match {
          case Array(name, version, hash) => (name, version) -> hash
          case _ => sys.error(s"Failed to parse line $line")
        }
      }
      .toMap
  }
}
