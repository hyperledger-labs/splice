// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.darutils

import better.files.File
import cats.syntax.either.*
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.daml.lf.archive.{Dar, DarDecoder}
import com.digitalasset.daml.lf.data.Ref.PackageId
import com.digitalasset.daml.lf.language.Ast.{Package, PackageMetadata}

import java.io.FileInputStream
import java.util.zip.ZipInputStream
import scala.util.Using

object DarResourcesGenerator {

  // TODO(tech-debt): consider moving this to a dedicated config file if it bugs us here
  private val minimumInitializations: Map[String, String] = Map(
    "splice-amulet" -> "0.1.14",
    "splice-amulet-name-service" -> "0.1.14",
    "splice-dso-governance" -> "0.1.19",
    "splice-wallet" -> "0.1.14",
    "splice-wallet-payments" -> "0.1.14",
    "splitwell" -> "0.1.14",
    "splice-validator-lifecycle" -> "0.1.5",
    "splice-util-batched-markers" -> "1.0.0",
    "splice-api-token-metadata-v1" -> "1.0.0",
    "splice-api-token-holding-v1" -> "1.0.0",
    "splice-api-token-transfer-instruction-v1" -> "1.0.0",
    "splice-api-token-allocation-v1" -> "1.0.0",
    "splice-api-token-allocation-request-v1" -> "1.0.0",
    "splice-api-token-allocation-instruction-v1" -> "1.0.0",
    "splice-token-test-trading-app" -> "1.0.0",
    "splice-api-token-holding-v2" -> "1.0.0",
    "splice-api-token-transfer-instruction-v2" -> "1.0.0",
    "splice-api-token-allocation-v2" -> "1.0.0",
    "splice-api-token-allocation-request-v2" -> "1.0.0",
    "splice-api-token-allocation-instruction-v2" -> "1.0.0",
    "splice-token-test-trading-app" -> "1.0.0",
    "splice-token-test-trading-app-v2" -> "1.0.0",
  )

  // fix the order to reduce the diff to the existing status quo
  // TODO(tech-debt): simplify / remove explicit ordering if this bugs us
  private val topLevelPackageOrder: Seq[String] = Seq(
    "splice-amulet",
    "splice-dso-governance",
    "splice-util-batched-markers",
    "splice-wallet",
    "splice-amulet-name-service",
    "splice-wallet-payments",
    "splitwell",
    "splice-validator-lifecycle",
  )
  private val tokenStandardProductionPackageOrder: Seq[String] = Seq(
    "splice-api-token-metadata-v1",
    "splice-api-token-holding-v1",
    "splice-api-token-transfer-instruction-v1",
    "splice-api-token-allocation-v1",
    "splice-api-token-allocation-request-v1",
    "splice-api-token-allocation-instruction-v1",
    "splice-api-token-holding-v2",
    "splice-api-token-transfer-instruction-v2",
    "splice-api-token-allocation-v2",
    "splice-api-token-allocation-request-v2",
    "splice-api-token-allocation-instruction-v2",
  )
  private val tokenStandardTestPackage: String = "splice-token-test-trading-app"
  private val tokenStandardTestPackageV2: String = "splice-token-test-trading-app-v2"

  final case class DarEntry(
      path: String,
      packageName: String,
      packageId: String,
      metadata: PackageMetadata,
      dependencyPackageIds: Set[String],
  )

  private def readDar(file: File): Dar[(PackageId, Package)] =
    Using.resource(new ZipInputStream(new FileInputStream(file.toJava))) { zipStream =>
      DarDecoder
        .readArchive(file.name, zipStream)
        .valueOr(err =>
          throw new IllegalArgumentException(s"Failed to decode dar ${file.name}: $err")
        )
    }

  def readDarEntry(file: File): Option[DarEntry] = {
    // Filenames look like "splice-amulet-0.1.18.dar" — split on the
    // last hyphen followed by a digit to separate name from version.
    // Accept 3 or more version segments so patched DARs such as
    // `splice-amulet-0.1.18.123.dar` (produced by prep-app-upgrade-test)
    // are still picked up.
    val versionPattern = """^(.+)-([0-9]+\.[0-9]+\.[0-9]+(?:\.[0-9]+)*)\.dar$""".r
    file.name match {
      case versionPattern(name, _) =>
        val dar = readDar(file)
        Some(
          DarEntry(
            path = file.name,
            packageName = name,
            packageId = dar.main._1,
            metadata = dar.main._2.metadata,
            dependencyPackageIds = dar.dependencies.map(_._1).toSet,
          )
        )
      case _ => None
    }
  }

  def scanDars(darsDir: File): Seq[DarEntry] =
    darsDir
      .list(_.extension.contains(".dar"))
      .toSeq
      .flatMap(readDarEntry)

  def groupByPackage(entries: Seq[DarEntry]): Map[String, Seq[DarEntry]] =
    entries.groupBy(_.packageName).view.mapValues(_.sortBy(_.metadata.version)).toMap

  def render(darsDir: File): String = {
    val entries = scanDars(darsDir)
    val grouped = groupByPackage(entries)

    val lines = Seq(
      "// DO NOT EDIT! This file is generated automatically by DarResourcesGenerator.scala via `sbt updateDarResources`",
      "package org.lfdecentralizedtrust.splice.environment",
      "import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}",
      "import com.digitalasset.daml.lf.language.Ast.PackageMetadata",
      "object DarResources {",
    ) ++
      indent(2, renderTokenStandard(grouped)) ++
      topLevelPackageOrder.flatMap { name =>
        val dars = grouped.getOrElse(
          name,
          sys.error(s"Package $name listed in topLevelPackageOrder but not present in daml/dars/"),
        )
        indent(2, renderPackage(name, dars, grouped))
      } ++
      renderPackageResources() ++
      Seq(
        """|  lazy val pkgIdToDarResource: Map[String, DarResource] =
           |    packageResources.view.flatMap(_.all).map(resource => resource.packageId -> resource).toMap
           |
           |  // We don't index the map by PackageMetadata because that type contains some additional
           |  // fields that don't matter.
           |  lazy val pkgMetadataToDarResource: Map[(PackageName, PackageVersion), DarResource] =
           |    packageResources.view
           |      .flatMap(_.all)
           |      .map(resource => (resource.metadata.name, resource.metadata.version) -> resource)
           |      .toMap
           |}""".stripMargin
      )

    lines.mkString("\n")
  }

  private val packageResourcesRenderOrder = topLevelPackageOrder.sorted

  private def renderPackageResources(): Seq[String] =
    Seq(
      "  lazy val packageResources: Seq[PackageResource] =",
      "  TokenStandard.allPackageResources ++ Seq(",
    ) ++
      packageResourcesRenderOrder.map(name => s"    DarResources.${camel(name)},") ++
      Seq(
        "  )",
        "",
      )

  private def renderTokenStandard(grouped: Map[String, Seq[DarEntry]]): Seq[String] = {
    val production = tokenStandardProductionPackageOrder.flatMap(name =>
      renderPackage(name, grouped.getOrElse(name, Nil), grouped)
    )
    val test = renderPackage(
      tokenStandardTestPackage,
      grouped.getOrElse(tokenStandardTestPackage, Nil),
      grouped,
    )
    val testV2 = renderPackage(
      tokenStandardTestPackageV2,
      grouped.getOrElse(tokenStandardTestPackageV2, Nil),
      grouped,
    )

    Seq("object TokenStandard {") ++
      indent(2, production ++ test ++ testV2) ++
      Seq(
        s"  val allProductionPackageResources = Seq(${tokenStandardProductionPackageOrder.map(camel).mkString(", ")})",
        s"  val allPackageResources = allProductionPackageResources :+ ${camel(tokenStandardTestPackage)} :+ ${camel(tokenStandardTestPackageV2)}",
        "}",
      )
  }

  private def renderPackage(
      name: String,
      dars: Seq[DarEntry],
      grouped: Map[String, Seq[DarEntry]],
  ): Seq[String] = {
    val minVersion = minimumInitializations.getOrElse(
      name,
      sys.error(s"No minimumInitialization configured for package $name"),
    )
    val latest = dars.lastOption.getOrElse(
      sys.error(s"No DARs found for package $name in daml/dars/")
    )
    val minimumDar = grouped
      .getOrElse(name, Nil)
      .find(_.metadata.version.toString == minVersion)
      .getOrElse(
        sys.error(s"Minimum version $minVersion for $name not found in daml/dars/")
      )

    dars.flatMap(dar => renderDarResource(varSuffix(dar), dar)) ++
      renderDarResource("current", latest.copy(path = s"$name-current.dar")) ++
      Seq(
        s"val ${camel(name)} = PackageResource(",
        s"  ${camel(name)}_current,",
        s"  ${camel(name)}_${varSuffix(minimumDar)},",
        s"  Seq(${dars.map(dar => s"${camel(name)}_${varSuffix(dar)}").mkString(", ")})",
        ")",
      )
  }

  private def renderDarResource(suffix: String, dar: DarEntry): Seq[String] =
    Seq(
      s"val ${camel(dar.packageName)}_${suffix} = DarResource(",
      s"   \"${dar.path}\",",
      s"   \"${dar.packageId}\",",
      s"   PackageMetadata(PackageName.assertFromString(\"${dar.metadata.name}\"), PackageVersion.assertFromString(\"${dar.metadata.version}\"), None),",
      s"   Set(${dar.dependencyPackageIds.map(id => s"\"$id\"").mkString(", ")}),",
      ")",
    )

  private def varSuffix(dar: DarEntry): String = dar.metadata.version.toString.replace(".", "_")

  private def camel(name: String): String = {
    val words = name.replace("splice-", "").split("-")
    words.toSeq match {
      case head +: tail => head + tail.map(_.capitalize).mkString
      case _ => ""
    }
  }

  private def indent(count: Int, lines: Seq[String]): Seq[String] =
    lines.map(" " * count + _)

  def main(args: Array[String]): Unit = {
    val outputFile = File(args(0))
    val darsDir = File(args(1))
    outputFile.parent.createDirectoryIfNotExists()
    outputFile
      .overwrite(render(darsDir))
      .discard[File]
  }
}
