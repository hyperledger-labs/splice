// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.build_tools

import better.files.*
import com.digitalasset.daml.lf.archive.DarParser
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}

import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.sys.process.*

object DarLockChecker {
  final case class Dar(
      packageName: PackageName,
      packageVersion: PackageVersion,
      packageId: String,
      filename: String,
  )

  def main(args: Array[String]): Unit = {
    args.toSeq match {
      case cmd +: outputFilename +: inputFilenames =>
        // This includes all freshly built DARs but not the checked in DARs.
        val builtDars: Seq[Dar] = readDars(inputFilenames)
        val nonTestBuiltDars = builtDars.filter(dar => !dar.packageName.endsWith("-test"))

        val darMap = toDarMap(builtDars)

        cmd match {
          case "check" =>
            // Check that the freshly built packages either match the
            // last release or have a different version number.
            checkPackageIdsImmutable(darMap, exhaustive = false)
            // Check that the freshly built non-test DARs match the checked in DARs
            checkDarHashes(nonTestBuiltDars)
            // Check all DARs in the lock file for immutability, we do that only after the
            // first two checks as it gives clearer errors on whether the problem
            // is not updating a checked in DAR or not updating the version.
            checkDarsLockImmutable()
            val checkedInDarMap = getCheckedInDarMap()
            val currentHashes = File(outputFilename).contentAsString
            val lockStr = getLockStr(checkedInDarMap ++ darMap)
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
            // Check that the freshly built packages either match the
            // last release or have a different version number.
            // This must always be the case so even in "update"
            // this is just a check.
            checkPackageIdsImmutable(darMap, exhaustive = false)
            // Copy the freshly built DARs to the checked in DARs.
            updateDars(nonTestBuiltDars)
            // Only read the checked in DARs here to make sure they
            // include the ones we just copied.
            val checkedInDarMap = getCheckedInDarMap()
            val lockStr = getLockStr(checkedInDarMap ++ darMap)
            val _ = File(outputFilename).overwrite(lockStr)
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

  private def checkPackageIdsImmutable(
      actual: Map[(PackageName, PackageVersion), String],
      exhaustive: Boolean = true,
  ): Unit = {
    val lastReleaseNumber = File("LATEST_RELEASE").contentAsString.strip
    val lastReleaseDarLock =
      s"git show refs/remotes/origin/release-line-$lastReleaseNumber:daml/dars.lock".!!
    val lastReleaseDars = parseDarsLock(lastReleaseDarLock)
    val mismatches = actual.flatMap { case (pkg, currentHash) =>
      lastReleaseDars
        .get(pkg)
        .flatMap { lastReleaseHash =>
          Option.when(currentHash != lastReleaseHash)((pkg, currentHash, lastReleaseHash))
        }
        .toList
    }
    if (!mismatches.isEmpty) {
      mismatches.foreach { case (pkg, currentHash, lastReleaseHash) =>
        System.err.println(s"Package $pkg changed hash from $currentHash to $lastReleaseHash")
      }
      sys.error("Some packages changed their hash, did you forget to bump the package versions?")
    }
    if (exhaustive) {
      lastReleaseDars.keys.foreach { case pkg @ (pkgName, _) =>
        if (!pkgName.endsWith("-test") && !actual.contains(pkg)) {
          sys.error(s"Package $pkg was in last release but is missing from current release")
        }
      }
    }
  }

  private def checkDarsLockImmutable(): Unit = {
    val currentDars = parseDarsLock(File("daml/dars.lock").contentAsString)
    checkPackageIdsImmutable(currentDars)
  }

  private def checkedInDarFile(dar: Dar) =
    File(s"daml/dars/${dar.packageName}-${dar.packageVersion}.dar")

  private def checkDarHashes(dars: Seq[Dar]): Unit = {
    dars.foreach { dar =>
      val currentHash = File(dar.filename).sha256
      val checkedInFile = checkedInDarFile(dar)
      val checkedInHash = Try(checkedInFile.sha256) match {
        case Success(s) => s
        case Failure(e) =>
          sys.error(s"Failed to read $checkedInFile, update checked-in DAR: $e")
      }
      if (currentHash != checkedInHash) {
        sys.error(
          s"Hash of DAR ${dar.filename} is ${currentHash} while the checked in DAR ${checkedInFile} has hash ${checkedInHash}, update the checked-in DAR"
        )
      }
    }
  }

  private def toDarMap(dars: Seq[Dar]): Map[(PackageName, PackageVersion), String] =
    dars.foldLeft(Map.empty[(PackageName, PackageVersion), String]) {
      case (map, dar) => {
        val _ = map
          .get((dar.packageName, dar.packageVersion))
          .foreach(_hash =>
            if (_hash != dar.packageId)
              sys.error(
                s"Conflicting package ids for version ${dar.packageVersion} of package ${dar.packageName}."
              )
          )
        map + ((dar.packageName, dar.packageVersion) -> dar.packageId)
      }
    }

  private def getLockStr(darMap: Map[(PackageName, PackageVersion), String]) =
    darMap
      .map({ case (name, version) -> hash => s"$name $version $hash" })
      .toSeq
      .sorted
      .mkString(System.lineSeparator())

  private def getCheckedInDarMap(): Map[(PackageName, PackageVersion), String] = {
    val checkedInDars = File("daml/dars").list(_.extension == Some(".dar")).toSeq
    toDarMap(readDars(checkedInDars.map(_.toString())))
  }

  private def readDars(filenames: Seq[String], timeout: Duration = 1.minutes): Seq[Dar] = {
    implicit val ec = ExecutionContext.global

    def readDar(filename: String): Dar = {
      val archive = DarParser.assertReadArchiveFromFile(File(filename).toJava)
      val hash = archive.main.getHash
      // low-level decoding of the protobuf instead of using the LF libraries
      // as we only care about the metadata
      // and can avoid decoding the full LF AST.
      val payload = com.digitalasset.daml.lf.archive.ArchivePayloadParser
        .assertFromByteString(archive.main.getPayload)
      val input = payload.getDamlLf2.newCodedInput()
      input.setRecursionLimit(1000)
      val pkg = com.digitalasset.daml.lf.archive.DamlLf2.Package.parseFrom(input)
      val metadata = pkg.getMetadata
      val internedStrings = pkg.getInternedStrings
      val name = PackageName.assertFromString(internedStrings(metadata.getNameInternedStr))
      val version = PackageVersion.assertFromString(internedStrings(metadata.getVersionInternedStr))
      Dar(name, version, hash, filename)
    }

    val darFutures: Seq[Future[Dar]] = filenames.map { filename =>
      Future {
        readDar(filename)
      }
    }
    Await.result(Future.sequence(darFutures), timeout)
  }

  private def updateDars(dars: Seq[Dar]): Unit = {
    dars.foreach { dar =>
      val checkedInFilename = checkedInDarFile(dar)
      File(dar.filename).copyTo(checkedInFilename, overwrite = true)
    }
  }

  private def isTestDar(packageName: String): Boolean =
    packageName.endsWith("-test")

  // Parse the contents of the dar lock file into
  // a map of (package name, package version) -> package id
  private def parseDarsLock(fileContent: String): Map[(PackageName, PackageVersion), String] = {
    fileContent
      .split("\n")
      .map { line =>
        line.split(" ") match {
          case Array(name, version, hash) =>
            (PackageName.assertFromString(name), PackageVersion.assertFromString(version)) -> hash
          case _ => sys.error(s"Failed to parse line $line")
        }
      }
      .toMap
  }
}
