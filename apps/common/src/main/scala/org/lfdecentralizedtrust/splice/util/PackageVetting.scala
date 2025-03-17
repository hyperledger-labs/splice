// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package org.lfdecentralizedtrust.splice.util

import cats.syntax.foldable.*
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.time.Clock
import com.digitalasset.canton.topology.SynchronizerId
import com.digitalasset.canton.tracing.TraceContext
import com.digitalasset.canton.util.ShowUtil.*
import com.digitalasset.daml.lf.data.Ref.PackageVersion
import org.lfdecentralizedtrust.splice.codegen.java.splice.amuletrules.AmuletRules
import org.lfdecentralizedtrust.splice.environment.{
  DarResources,
  PackageIdResolver,
  ParticipantAdminConnection,
}

import java.time.Instant
import scala.concurrent.{ExecutionContext, Future}

class PackageVetting(
    packages: Set[PackageIdResolver.Package],
    clock: Clock,
    participantAdminConnection: ParticipantAdminConnection,
    override val loggerFactory: NamedLoggerFactory,
)(implicit ec: ExecutionContext)
    extends NamedLogging {

  def vetCurrentPackages(
      domainId: SynchronizerId,
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
  )(implicit tc: TraceContext): Future[Unit] = {
    val schedule = AmuletConfigSchedule(amuletRules)
    val currentPackageConfig = schedule.getConfigAsOf(clock.now).packageConfig
    val currentRequiredPackages =
      packages.map(pkg => pkg -> PackageIdResolver.readPackageVersion(currentPackageConfig, pkg))
    currentRequiredPackages.toSeq.traverse_ { case (pkg, packageVersion) =>
      DarResources
        .lookupAllPackageVersions(pkg.packageName)
        .filter(_.metadata.version <= packageVersion)
        .traverse_(darResource =>
          vetPackage(
            domainId,
            pkg,
            darResource.metadata.version,
            None,
          )
        )
    }
  }

  def vetPackages(
      domainId: SynchronizerId,
      amuletRules: Contract[AmuletRules.ContractId, AmuletRules],
  )(implicit tc: TraceContext): Future[Unit] = {
    val schedule = AmuletConfigSchedule(amuletRules)
    val vettingSchedule =
      associatePackageVersionsByEarliestVettingDate(amuletRules.createdAt, schedule)
    vettingSchedule.toSeq.traverse_ { case ((packageName, packageVersion), validFrom) =>
      vetPackage(domainId, packageName, packageVersion, Some(validFrom))
    }
  }

  private def vetPackage(
      domainId: SynchronizerId,
      pkg: PackageIdResolver.Package,
      packageVersion: PackageVersion,
      validFrom: Option[Instant],
  )(implicit tc: TraceContext): Future[Unit] = {
    for {
      // Upload the version required by current config, and log an error if it is not part of the deployed release
      _ <- DarResources.lookupPackageMetadata(pkg.packageName, packageVersion) match {
        case None =>
          validFrom match {
            case Some(time) =>
              logger.warn(
                show"Package ${pkg.packageName} is required in version ${packageVersion.toString()} after $time according to AmuletConfig but this version is not part of the deployed release, upgrade before $time to avoid any issues"
              )
            case None =>
              logger.error(
                show"Package ${pkg.packageName} is required in version ${packageVersion.toString()} according to AmuletConfig but this version is not part of the deployed release, upgrade immediately to avoid any issues"
              )
          }
          Future.unit
        case Some(resource) =>
          participantAdminConnection.vetDar(
            domainId,
            resource,
            fromDate = validFrom,
          )
      }
    } yield ()
  }

  private def associatePackageVersionsByEarliestVettingDate(
      createdAt: Instant,
      amuletConfigSchedule: AmuletConfigSchedule,
  ) = {
    (amuletConfigSchedule.futureConfigs :+ (createdAt -> amuletConfigSchedule.initialConfig))
      .flatMap { case (time, config) =>
        packages.map(pkg =>
          time -> (pkg -> PackageIdResolver.readPackageVersion(config.packageConfig, pkg))
        )
      }
      .groupMapReduce(_._2)(_._1) { case (time1, time2) =>
        if (time1.isBefore(time2)) time1 else time2
      }
  }

}
