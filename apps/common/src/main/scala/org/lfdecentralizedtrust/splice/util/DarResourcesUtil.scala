// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.LfPackageId
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.environment.DarResource
import org.lfdecentralizedtrust.splice.environment.DarResources.{
  packageResources,
  pkgIdToDarResource,
  pkgMetadataToDarResource,
}

object DarResourcesUtil {

  def lookupPackageId(packageId: String): Option[DarResource] =
    pkgIdToDarResource.get(packageId)

  def getDarResources(packageIds: Seq[String]): Seq[DarResource] =
    packageIds.flatMap(lookupPackageId)

  def lookupPackageMetadata(name: PackageName, version: PackageVersion): Option[DarResource] =
    pkgMetadataToDarResource.get((name, version))

  def lookupAllPackageVersions(name: PackageName): Seq[DarResource] =
    packageResources.view.flatMap(_.all).toSeq.filter(_.metadata.name == name)

  def getRequiredPackageVersions(
      name: PackageName,
      upToRequiredVersion: PackageVersion,
      enableUnsupportedDarsUnvetting: Boolean,
      latestPackagesOnly: Boolean = false,
  ): Seq[DarResource] = {
    val minimumInitializationVersion = lookupMinimumPackageResource(name).metadata.version
    // TODO(hyperledger-labs/splice#4049): remove enableUnsupportedDarsUnvetting
    packageResources.view
      .flatMap(_.all)
      .toSeq
      .filter(_.metadata.name == name)
      .filter(pkg => {
        val version = pkg.metadata.version
        if (enableUnsupportedDarsUnvetting) {
          (!latestPackagesOnly && minimumInitializationVersion <= version && version < upToRequiredVersion) || version == upToRequiredVersion
        } else {
          (!latestPackagesOnly && version < upToRequiredVersion) || version == upToRequiredVersion
        }
      })
      .distinct
  }

  def filterUnsupportedPackageVersions(packageIds: Seq[LfPackageId]): Seq[DarResource] = {
    val unsupportedDarResources = packageIds
      .diff(supportedPackageVersions.map(_.packageId))
      .flatMap(pkg => pkgIdToDarResource.get(pkg))
    unsupportedDarResources.filter(pkg =>
      packageResources
        .find(_.latest.metadata.name == pkg.metadata.name)
        .getOrElse(
          throw new NoSuchElementException(s"Could not find PackageResource ${pkg.metadata.name}.")
        )
        .minimumInitialization
        .metadata
        .version > pkg.metadata.version
    )
  }

  private val supportedPackageVersions: Seq[DarResource] =
    packageResources.flatMap(pkg =>
      pkg.all.filter(p => p.metadata.version >= pkg.minimumInitialization.metadata.version)
    )

  private def lookupMinimumPackageResource(name: PackageName): DarResource =
    packageResources
      .find(_.latest.metadata.name == name)
      .getOrElse(throw new NoSuchElementException(s"Could not find PackageResource for $name."))
      .minimumInitialization
}
