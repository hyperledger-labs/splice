// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import com.digitalasset.canton.LfPackageId
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.daml.lf.data.Ref.{PackageName, PackageVersion}
import org.lfdecentralizedtrust.splice.environment.DarResource
import org.lfdecentralizedtrust.splice.environment.DarResources.{
  packageResources,
  pkgIdToDarResource,
  pkgMetadataToDarResource,
}

object DarResourcesUtil extends NamedLogging {

  override protected def loggerFactory: NamedLoggerFactory = NamedLoggerFactory.root

  val minimalPackageVersions: Seq[DarResource] = packageResources.flatMap(pkg =>
    pkg.all.filter(p => p.metadata.version == pkg.minimumInitialization.metadata.version)
  )

  val supportedPackageVersions: Seq[DarResource] =
    packageResources.flatMap(pkg =>
      pkg.all.filter(p => p.metadata.version >= pkg.minimumInitialization.metadata.version)
    )

  def lookupPackageId(packageId: String): Option[DarResource] =
    pkgIdToDarResource.get(packageId)

  def getDarResources(packageIds: Seq[String]): Seq[DarResource] =
    packageIds.flatMap(lookupPackageId)

  def lookupPackageMetadata(name: PackageName, version: PackageVersion): Option[DarResource] =
    pkgMetadataToDarResource.get((name, version))

  def lookupAllPackageVersions(name: PackageName): Seq[DarResource] =
    packageResources.view.flatMap(_.all).toSeq.filter(_.metadata.name == name)

  // TODO(hyperledger-labs/splice#4049): remove enableUnsupportedDarsUnvetting once it's on MainNet
  def getRequiredPackageVersions(
      name: PackageName,
      upToRequiredVersion: PackageVersion,
      enableUnsupportedDarsUnvetting: Boolean,
      latestPackagesOnly: Boolean = false,
      additionalPackagesToUnvet: Map[PackageName, Set[PackageVersion]] = Map.empty,
  )(implicit tc: TraceContext): Seq[DarResource] = {
    val minimumInitializationVersion = lookupMinimumPackageResource(name).metadata.version
    // Ignore unsupported versions that are smaller or equal to the minimum initialization version in order to keep a minimal set of dars
    val unsupportedVersions = additionalPackagesToUnvet
      .getOrElse(name, Set.empty)
      .filter { v =>
        if (minimumInitializationVersion < v && v <= upToRequiredVersion) {
          true
        } else {
          logger.debug(
            s"Version $v of package $name configured in `additionalPackagesToUnvet` is smaller or equal to the minimum initialization version $minimumInitializationVersion or larger than $upToRequiredVersion."
          )
          false
        }
      }
    packageResources.view
      .flatMap(_.all)
      .toSeq
      .filter(_.metadata.name == name)
      .filter(pkg => {
        val version = pkg.metadata.version
        if (unsupportedVersions.contains(version) && enableUnsupportedDarsUnvetting) {
          false
        } else if (enableUnsupportedDarsUnvetting) {
          (!latestPackagesOnly && minimumInitializationVersion <= version && version < upToRequiredVersion) || version == upToRequiredVersion
        } else {
          (!latestPackagesOnly && version < upToRequiredVersion) || version == upToRequiredVersion
        }
      })
      .distinct
  }

  def filterUnsupportedPackageVersions(
      vettedPackageIds: Seq[LfPackageId],
      enableUnsupportedDarsUnvetting: Boolean,
      latestPackagesOnly: Boolean,
      additionalPackageIdsToUnvet: Map[PackageName, Set[PackageVersion]],
  )(implicit tc: TraceContext): Seq[DarResource] = {
    val allSupportedVersionsPackageIds =
      packageResources
        .flatMap(pkg =>
          getRequiredPackageVersions(
            pkg.latest.metadata.name,
            pkg.latest.metadata.version,
            enableUnsupportedDarsUnvetting,
            latestPackagesOnly,
            additionalPackageIdsToUnvet,
          )
        )
        .map(_.packageId)
    vettedPackageIds
      .filterNot(allSupportedVersionsPackageIds.contains(_))
      .flatMap(pkg => pkgIdToDarResource.get(pkg))
  }

  private def lookupMinimumPackageResource(name: PackageName): DarResource =
    packageResources
      .find(_.latest.metadata.name == name)
      .getOrElse(throw new NoSuchElementException(s"Could not find PackageResource for $name."))
      .minimumInitialization
}
