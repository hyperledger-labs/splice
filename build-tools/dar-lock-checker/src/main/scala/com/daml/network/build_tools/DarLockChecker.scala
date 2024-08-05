// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.network.build_tools

import better.files.*
import com.digitalasset.daml.lf.archive.{DarDecoder, DarParser}

import scala.sys.process.*

object DarLockChecker {
  final case class Dar(
      packageName: String,
      packageVersion: String,
      filename: String,
  )

  def main(args: Array[String]): Unit = {
    args.toSeq match {
      case cmd +: outputFileName +: inputFileNames =>
        val (dars, nonTestDars) = inputFileNames
          .map(filename => {
            val hash = DarParser.assertReadArchiveFromFile(File(filename).toJava).main.getHash
            val metadata =
              DarDecoder.assertReadArchiveFromFile(File(filename).toJava).main._2.metadata
            ((metadata.name, metadata.version.toString()) -> hash, filename)
          })
          .foldLeft((Map.empty[(String, String), String], Seq.empty[Dar])) {
            case ((map, nonTestDars), (((name, version), hash), filename)) => {
              val _ = map
                .get((name, version))
                .foreach(_hash =>
                  if (_hash != hash)
                    sys.error(
                      s"Conflicting hashes for version ${version} of package ${name}. If you modified daml models, please bump the package version."
                    )
                )
              val newNonTestDars = Seq(Dar(name, version, filename)).filter(_ =>
                !name.endsWith("-test") && filename.endsWith("-current.dar")
              ) ++ nonTestDars
              (map + ((name, version) -> hash), newNonTestDars)
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
            checkDarHashes(nonTestDars)
          case "update" =>
            val _ = File(outputFileName).overwrite(lockStr)
            checkDarsLockImmutable()
            updateDars(nonTestDars)
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

  private def checkedInDarFile(dar: Dar) =
    File(s"daml/dars/${dar.packageName}-${dar.packageVersion}.dar")

  private def checkDarHashes(dars: Seq[Dar]): Unit = {
    dars.foreach { dar =>
      val currentHash = File(dar.filename).sha256
      val checkedInFile = checkedInDarFile(dar)
      val checkedInHash = checkedInFile.sha256
      if (currentHash != checkedInHash) {
        sys.error(
          s"Hash of DAR ${dar.filename} is ${currentHash} while the checked in DAR ${checkedInFile} has hash ${checkedInHash}, either update the version or update the checked-in DAR"
        )
      }
    }
  }

  private def updateDars(dars: Seq[Dar]): Unit = {
    dars.foreach { dar =>
      val checkedInFilename = checkedInDarFile(dar)
      File(dar.filename).copyTo(checkedInFilename, overwrite = true)
    }
  }

  private def isTestDar(packageName: String): Boolean =
    packageName.endsWith("-test")

  private def isCurrentDar(packageName: String): Boolean =
    packageName.endsWith("-test")

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
