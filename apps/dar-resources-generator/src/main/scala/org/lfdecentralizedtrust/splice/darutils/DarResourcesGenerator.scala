// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.darutils

import better.files.File
import com.digitalasset.canton.discard.Implicits.DiscardOps

object DarResourcesGenerator {
  def render(): String = {
    val lines = Seq(
      "package org.lfdecentralizedtrust.splice.environment",
      "import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}",
      "import com.digitalasset.daml.lf.language.Ast.PackageMetadata",
      "object DarResources {",
    ) ++
      indent(2, renderTokenStandard()) ++
      Seq(
        DarResources.amulet,
        DarResources.dsoGovernance,
        DarResources.batchedMarkers,
        DarResources.wallet,
        DarResources.amuletNameService,
        DarResources.walletPayments,
        DarResources.splitwell,
        DarResources.validatorLifecycle,
      ).flatMap(pkg => indent(2, render(pkg))) :+
      """|  lazy val packageResources: Seq[PackageResource] =
       |  TokenStandard.allPackageResources ++ Seq(
       |    DarResources.amulet,
       |    DarResources.amuletNameService,
       |    DarResources.utilBatchedMarkers,
       |    DarResources.dsoGovernance,
       |    DarResources.splitwell,
       |    DarResources.validatorLifecycle,
       |    DarResources.wallet,
       |    DarResources.walletPayments,
       |    DarResources.walletPayments,
       |  )
       |
       |  private lazy val pkgIdToDarResource: Map[String, DarResource] =
       |    packageResources.view.flatMap(_.all).map(resource => resource.packageId -> resource).toMap
       |
       |  // We don't index the map by PackageMetadata because that type contains some additional
       |  // fields that don't matter.
       |  private lazy val pkgMetadataToDarResource: Map[(PackageName, PackageVersion), DarResource] =
       |    packageResources.view
       |      .flatMap(_.all)
       |      .map(resource => (resource.metadata.name, resource.metadata.version) -> resource)
       |      .toMap
       |
       |  def lookupPackageId(packageId: String): Option[DarResource] =
       |    pkgIdToDarResource.get(packageId)
       |
       |  def getDarResources(packageIds: Seq[String]): Seq[DarResource] =
       |    packageIds.flatMap(lookupPackageId)
       |
       |  def lookupPackageMetadata(name: PackageName, version: PackageVersion): Option[DarResource] =
       |    pkgMetadataToDarResource.get((name, version))
       |
       |  def lookupAllPackageVersions(name: PackageName): Seq[DarResource] =
       |    packageResources.view.flatMap(_.all).toSeq.filter(_.metadata.name == name)
       |}""".stripMargin
    lines.mkString("\n")
  }

  def renderTokenStandard(): Seq[String] =
    Seq("object TokenStandard {") ++
      indent(2, DarResources.TokenStandard.allPackageResources.flatMap(render(_))) ++
      Seq(
        s"  val allProductionPackageResources = Seq(${DarResources.TokenStandard.allProductionPackageResources
            .map(pkg => varPkgName(pkg.latest))
            .mkString(", ")})",
        s"  val allPackageResources = allProductionPackageResources :+ ${varPkgName(DarResources.TokenStandard.tokenTestTradingApp.latest)}",
        "}",
      )

  def render(pkgResource: PackageResource): Seq[String] = {
    pkgResource.others.flatMap(dar => render(varPkgVersion(dar), dar)) ++
      render("current", pkgResource.latest) ++
      Seq(
        s"val ${varPkgName(pkgResource.latest)} = PackageResource(",
        s"  ${varPkgName(pkgResource.latest)}_current,",
        s"  ${varPkg(pkgResource.minimumInitialization)},",
        s"  Seq(${pkgResource.others.map(pkg => s"${varPkg(pkg)}").mkString(", ")})",
        ")",
      )

  }

  def render(varSuffix: String, resource: DarResource): Seq[String] =
    Seq(
      s"val ${varPkgName(resource)}_${varSuffix} = DarResource(",
      s"   \"${resource.path}\",",
      s"   \"${resource.packageId}\",",
      s"   PackageMetadata(PackageName.assertFromString(\"${resource.metadata.name}\"), PackageVersion.assertFromString(\"${resource.metadata.version}\"), None),",
      s"   Set(${resource.dependencyPackageIds.map(pkgId => s"\"${pkgId}\"").mkString(", ")}),",
      ")",
    )

  def indent(count: Int, lines: Seq[String]): Seq[String] =
    lines.map(" " * count + _)

  def varPkg(resource: DarResource): String = s"${varPkgName(resource)}_${varPkgVersion(resource)}"

  def varPkgName(resource: DarResource): String = {
    val words = resource.metadata.name.replace("splice-", "").split("-")
    words.toSeq match {
      case head +: tail => head + tail.map(_.capitalize).mkString
      case _ => ""
    }
  }

  def varPkgVersion(resource: DarResource): String =
    resource.metadata.version.toString.replace(".", "_")

  def main(args: Array[String]): Unit = {
    val file = File(args(0))
    file.parent.createDirectoryIfNotExists()
    file
      .overwrite(
        render()
      )
      .discard[File]
  }
}
